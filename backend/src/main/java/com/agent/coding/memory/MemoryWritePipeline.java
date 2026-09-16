package com.agent.coding.memory;

import com.agent.coding.agent.AgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memory write pipeline (ADR-0009) — Java counterpart of QwenPaw's
 * MemoryMiddleware (on_reply / on_compress_context). Routed from
 * {@code ToolGuardHook}:
 *
 * <ul>
 *   <li><b>PRE_CALL</b> — capture the turn's user message into the pending
 *       list and hot-inject workspace prompt files (AGENTS.md/SOUL.md/
 *       PROFILE.md) plus the backend memory prompt into the system message
 *       (QwenPaw PromptBuilder semantics: re-read from disk every call);</li>
 *   <li><b>POST_CALL</b> — append the final reply; when the pending turn
 *       count reaches the configured interval, flush the accumulated turns
 *       to {@code MemoryBackend.remember} (trigger=periodic).</li>
 * </ul>
 *
 * <p>Pending turns persist to {@code memory/.auto_memory_state.json} so a
 * restart (or a crash mid-flush) does not silently drop turns. Degradation
 * is user-visible (QwenPaw #7663): an unavailable backend emits one inbox
 * warning per agent, reset on recovery. Disabled by default — enable via
 * {@code reme_light_memory_config.auto_memory_config.enabled}. Automated
 * request sources (cron etc.) share the agent runtime and are therefore
 * gated by this switch rather than detected separately.
 */
@Component
public class MemoryWritePipeline {

    private static final Logger log = LoggerFactory.getLogger(MemoryWritePipeline.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Workspace prompt files hot-loaded into the system message (QwenPaw PromptBuilder set). */
    private static final List<String> PROMPT_FILES = List.of("AGENTS.md", "SOUL.md", "PROFILE.md");
    private static final int MAX_PROMPT_FILE_CHARS = 64 * 1024;
    private static final int MAX_TURN_CHARS = 4000;
    private static final String STATE_FILE = ".auto_memory_state.json";
    private static final String INBOX_SOURCE = "memory";

    private final MemoryBackendRegistry registry;
    private final com.agent.coding.inbox.InboxStore inboxStore;
    private final ConcurrentHashMap<String, AgentState> states = new ConcurrentHashMap<>();

    private static final class AgentState {
        final List<String[]> pending = new ArrayList<>(); // {user, assistant}
        boolean unavailableNotified;
    }

    public MemoryWritePipeline(MemoryBackendRegistry registry,
                               com.agent.coding.inbox.InboxStore inboxStore) {
        this.registry = registry;
        this.inboxStore = inboxStore;
    }

    /** PRE_CALL branch: prompt file injection + pending turn capture. */
    @SuppressWarnings("unused")
    public void onPreCall(PreCallEvent event) {
        String agentId = agentIdOf(event);
        try {
            injectPromptFiles(event, agentId);
        } catch (Exception e) {
            log.debug("[memory-write] prompt injection skipped for '{}': {}", agentId, e.getMessage());
        }
        try {
            String userText = latestUserText(event.getInputMessages());
            if (userText != null && !userText.isBlank() && !userText.strip().startsWith("/")) {
                recordPending(agentId, userText, null);
            }
        } catch (Exception e) {
            log.debug("[memory-write] pending capture skipped for '{}': {}", agentId, e.getMessage());
        }
    }

    /** POST_CALL branch: finalize the turn and maybe flush. */
    @SuppressWarnings("unused")
    public void onPostCall(PostCallEvent event) {
        String agentId = agentIdOf(event);
        try {
            String reply = finalReplyText(event.getFinalMessage());
            recordPending(agentId, null, reply);
            maybeFlush(agentId);
        } catch (Exception e) {
            log.debug("[memory-write] turn finalize skipped for '{}': {}", agentId, e.getMessage());
        }
    }

    // ── Pending turns ────────────────────────────────────────────────

    private void recordPending(String agentId, String userText, String assistantText) {
        AgentState state = states.computeIfAbsent(agentId, k -> loadState(agentId));
        synchronized (state) {
            if (userText != null) {
                state.pending.add(new String[]{truncate(userText), ""});
            }
            if (assistantText != null && !state.pending.isEmpty()) {
                String[] last = state.pending.get(state.pending.size() - 1);
                last[1] = truncate(assistantText);
            }
            persistState(agentId, state);
        }
    }

    private void maybeFlush(String agentId) {
        Map<String, Object> cfg = autoMemoryConfig(agentId);
        if (!Boolean.TRUE.equals(com.agent.coding.skill.SkillService.bool(cfg.get("enabled"), false))) {
            return;
        }
        int interval = 1;
        Object raw = cfg.get("interval");
        if (raw instanceof Number n && n.intValue() > 0) {
            interval = n.intValue();
        }
        MemoryBackend backend = registry.resolve(agentId);
        if (backend == null) {
            notifyUnavailable(agentId);
            return;
        }
        notifyRecovered(agentId);
        AgentState state = states.computeIfAbsent(agentId, k -> new AgentState());
        List<String[]> batch = new ArrayList<>();
        synchronized (state) {
            if (state.pending.size() < interval) {
                return;
            }
            batch.addAll(state.pending);
            state.pending.clear();
            persistState(agentId, state);
        }
        String content = renderBatch(batch);
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("trigger", "periodic");
            metadata.put("turns", batch.size());
            metadata.put("backend", backend.id());
            // Routes the write to this agent's workspace only (ADR-0014);
            // the summary backend also uses it to pick the LLM slot.
            metadata.put("agent_id", agentId);
            backend.remember(content, metadata);
            log.info("[memory-write] flushed {} turn(s) for agent '{}' via backend '{}'",
                    batch.size(), agentId, backend.id());
        } catch (Exception e) {
            log.warn("[memory-write] remember() failed for agent '{}': {}", agentId, e.getMessage());
            inboxStore.appendEvent(agentId, INBOX_SOURCE, "", "auto_memory_result", "error",
                    "记忆写入失败", "自动记忆写入失败: " + e.getMessage(), "error",
                    Map.of("trigger", "periodic", "error", String.valueOf(e.getMessage())));
            // Restore the batch so the turns are not lost.
            AgentState s = states.computeIfAbsent(agentId, k -> new AgentState());
            synchronized (s) {
                s.pending.addAll(0, batch);
                persistState(agentId, s);
            }
        }
    }

    private static String renderBatch(List<String[]> batch) {
        StringBuilder sb = new StringBuilder("会话记忆摘录（最近 ");
        sb.append(batch.size()).append(" 轮）:\n");
        for (String[] turn : batch) {
            sb.append("\n用户: ").append(turn[0].isBlank() ? "(无)" : turn[0]).append("\n");
            if (!turn[1].isBlank()) {
                sb.append("助手: ").append(turn[1]).append("\n");
            }
        }
        return sb.toString();
    }

    // ── Degradation visibility (QwenPaw #7663) ──────────────────────

    private void notifyUnavailable(String agentId) {
        AgentState state = states.computeIfAbsent(agentId, k -> new AgentState());
        synchronized (state) {
            if (state.unavailableNotified) {
                return;
            }
            state.unavailableNotified = true;
        }
        inboxStore.appendEvent(agentId, INBOX_SOURCE, "", "auto_memory_result", "warning",
                "记忆后端不可用",
                "自动记忆已启用但当前没有可用的记忆后端，写入已暂停。"
                        + "请检查 memory_manager_backend 配置。",
                "warning", Map.of("backend", String.valueOf(
                        MemoryBackendRegistry.configuredBackendId(agentId))));
    }

    private void notifyRecovered(String agentId) {
        AgentState state = states.computeIfAbsent(agentId, k -> new AgentState());
        synchronized (state) {
            state.unavailableNotified = false;
        }
    }

    // ── AGENT.md hot injection (QwenPaw PromptBuilder) ───────────────

    private void injectPromptFiles(PreCallEvent event, String agentId) {
        Path workspace;
        try {
            workspace = AgentStore.workspaceDirForAgent(agentId);
        } catch (Exception e) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String name : PROMPT_FILES) {
            Path file = workspace.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.isBlank()) {
                    continue;
                }
                if (text.length() > MAX_PROMPT_FILE_CHARS) {
                    text = text.substring(0, MAX_PROMPT_FILE_CHARS) + "\n…[截断]";
                }
                sb.append("\n\n# ").append(name).append("\n").append(text.strip());
            } catch (IOException e) {
                log.debug("[memory-write] read {} failed: {}", name, e.getMessage());
            }
        }
        String backendPrompt = "";
        MemoryBackend backend = registry.resolve(agentId);
        if (backend != null) {
            backendPrompt = backend.getMemoryPrompt();
        }
        if (backendPrompt != null && !backendPrompt.isBlank()) {
            sb.append("\n\n# Memory\n").append(backendPrompt);
        }
        if (sb.isEmpty()) {
            return;
        }
        Msg sys = event.getSystemMessage();
        String existing = sys == null ? "" : sys.getTextContent();
        if (existing.contains("AGENTS.md") && existing.contains("# Memory")) {
            return; // already injected (e.g. nested agent calls)
        }
        event.appendSystemContent(sb.toString());
    }

    // ── Config / state persistence ───────────────────────────────────

    private static Map<String, Object> autoMemoryConfig(String agentId) {
        try {
            Map<String, Object> running = AgentStore.getRunningConfig(agentId);
            Object reme = running.get("reme_light_memory_config");
            if (reme instanceof Map<?, ?> m) {
                Object auto = m.get("auto_memory_config");
                if (auto instanceof Map<?, ?> a) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : a.entrySet()) {
                        out.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    return out;
                }
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private AgentState loadState(String agentId) {
        AgentState state = new AgentState();
        try {
            Path file = AgentStore.workspaceDirForAgent(agentId)
                    .resolve("memory").resolve(STATE_FILE);
            if (Files.isRegularFile(file)) {
                Map<?, ?> raw = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), Map.class);
                Object pending = raw.get("pending");
                if (pending instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof List<?> pair && pair.size() == 2) {
                            state.pending.add(new String[]{
                                    String.valueOf(pair.get(0)), String.valueOf(pair.get(1))});
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[memory-write] load state failed for '{}': {}", agentId, e.getMessage());
        }
        return state;
    }

    private void persistState(String agentId, AgentState state) {
        try {
            Path file = AgentStore.workspaceDirForAgent(agentId)
                    .resolve("memory").resolve(STATE_FILE);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(STATE_FILE + ".tmp");
            List<List<String>> pending = new ArrayList<>();
            for (String[] pair : state.pending) {
                pending.add(List.of(pair[0], pair[1]));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pending", pending);
            Files.writeString(tmp, MAPPER.writeValueAsString(out), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.debug("[memory-write] persist state failed for '{}': {}", agentId, e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String latestUserText(List<Msg> msgs) {
        if (msgs == null) {
            return null;
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg m = msgs.get(i);
            if (m.getRole() == MsgRole.USER) {
                return m.getTextContent();
            }
        }
        return null;
    }

    private static String finalReplyText(Msg finalMessage) {
        return finalMessage == null ? "" : finalMessage.getTextContent();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() > MAX_TURN_CHARS
                ? stripped.substring(0, MAX_TURN_CHARS) + "…" : stripped;
    }

    private static String agentIdOf(HookEvent event) {
        try {
            if (event.getAgent() != null && event.getAgent().getName() != null) {
                return event.getAgent().getName();
            }
        } catch (Exception ignored) {
        }
        return "default";
    }
}
