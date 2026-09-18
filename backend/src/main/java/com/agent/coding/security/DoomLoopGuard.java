package com.agent.coding.security;

import com.agent.coding.agent.AgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.RuntimeContextAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Doom-loop detection (QwenPaw {@code loop/gates/doom_loop.py} port):
 * a sliding-window repetition check over (tool name + args hash) per
 * session with staged escalation.
 *
 * <ul>
 *   <li>{@link #WARN_AFTER} identical calls in a row → the call is denied
 *       with a change-of-approach warning (the model sees the denial text
 *       instead of a wasted execution);</li>
 *   <li>{@link #STOP_AFTER} → hard denial telling the model to stop and
 *       summarize.</li>
 * </ul>
 *
 * <p>State resets when a different call breaks the pattern or a new turn
 * starts ({@link #resetTurn} from the PRE_CALL hook event).
 */
@Component
public class DoomLoopGuard implements RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(DoomLoopGuard.class);

    /** Identical (tool, args) calls in a row before the warning fires. */
    static final int WARN_AFTER = 3;
    /** Consecutive identical calls before the pattern is hard-denied. */
    static final int STOP_AFTER = 6;
    /** QwenPaw parity: only the first 2 KB of the serialized args are hashed. */
    private static final int HASH_INPUT_CHARS = 2048;
    private static final int MAX_SESSIONS = 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class SessionState {
        String lastTool;
        String lastHash;
        int consecutive;
    }

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ThreadLocal<RuntimeContext> ctxHolder = new ThreadLocal<>();

    @Override
    public void setRuntimeContext(RuntimeContext ctx) {
        if (ctx == null) {
            ctxHolder.remove();
        } else {
            ctxHolder.set(ctx);
        }
    }

    /**
     * Null = allow; non-null = denial reason for the tool call. Resolves
     * the session key from the event, records the call and escalates when
     * the previous call was identical.
     */
    public String check(io.agentscope.core.hook.HookEvent event, String toolName,
                        Map<String, Object> input) {
        return check(sessionOf(event), toolName, input);
    }

    /** Null = allow; non-null = denial reason, using live {@code security.doom_loop} config. */
    public String check(String sessionId, String toolName, Map<String, Object> input) {
        return check(sessionId, toolName, input, doomLoopSection());
    }

    /**
     * Config-explicit variant (testable without touching agents.json).
     * Recognized keys: {@code enabled} (default true), {@code warn_after}
     * (default {@link #WARN_AFTER}, min 2), {@code stop_after} (default
     * {@link #STOP_AFTER}, must exceed warn_after).
     */
    public String check(String sessionId, String toolName, Map<String, Object> input,
                        Map<String, Object> config) {
        if (config != null && !com.agent.coding.skill.SkillService.bool(config.get("enabled"), true)) {
            return null;
        }
        int warn = intOption(config, "warn_after", WARN_AFTER);
        int stop = intOption(config, "stop_after", STOP_AFTER);
        if (stop <= warn) {
            stop = warn + 1;
        }
        return check(sessionId, toolName, input, warn, stop);
    }

    /** Null = allow; non-null = denial reason, with explicit thresholds. */
    public String check(String sessionId, String toolName, Map<String, Object> input,
                        int warnAfter, int stopAfter) {
        if (sessionId == null || sessionId.isBlank()
                || toolName == null || toolName.isBlank()) {
            return null;
        }
        if (sessions.size() > MAX_SESSIONS) {
            sessions.clear(); // defensive bound; per-turn reset keeps this cold
        }
        String hash = argsHash(input);
        SessionState st = sessions.computeIfAbsent(sessionId, k -> new SessionState());
        synchronized (st) {
            if (!toolName.equals(st.lastTool) || !hash.equals(st.lastHash)) {
                st.lastTool = toolName;
                st.lastHash = hash;
                st.consecutive = 1;
                return null;
            }
            st.consecutive++;
            if (st.consecutive >= stopAfter) {
                log.warn("[doom-loop] session {} hard-stopped after {} identical '{}' calls",
                        sessionId, st.consecutive, toolName);
                return "🛑 死循环保护: 工具 '" + toolName + "' 已以完全相同的参数连续调用 "
                        + st.consecutive + " 次并被拒绝。请停止重复,总结当前进展并向用户说明情况。";
            }
            if (st.consecutive >= warnAfter) {
                log.info("[doom-loop] session {} warning at {} identical '{}' calls",
                        sessionId, st.consecutive, toolName);
                return "⚠️ 疑似死循环: 工具 '" + toolName + "' 以完全相同的参数连续调用 "
                        + st.consecutive + " 次,本次未执行。请改变方法或调整参数;"
                        + "如果重复确实是必要的,请先向用户说明原因。";
            }
            return null;
        }
    }

    /** {@code security.doom_loop} subsection, mirroring ToolGuardService. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> doomLoopSection() {
        Map<String, Object> config = AgentStore.loadConfig();
        Object sec = config.get("security");
        if (sec instanceof Map<?, ?> m && m.get("doom_loop") instanceof Map<?, ?> d) {
            return new LinkedHashMap<>((Map<String, Object>) d);
        }
        return new LinkedHashMap<>();
    }

    private static int intOption(Map<String, Object> config, String key, int def) {
        if (config == null) return def;
        Object v = config.get(key);
        try {
            return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** Clear the pattern state for the current turn's session (PRE_CALL). */
    public void resetTurn(io.agentscope.core.hook.HookEvent event) {
        reset(sessionOf(event));
    }

    /** Clear the pattern state for one session key. */
    public void reset(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** Session id: injected runtime context first, then agent state. */
    private String sessionOf(io.agentscope.core.hook.HookEvent event) {
        RuntimeContext ctx = ctxHolder.get();
        if (ctx != null && ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
            return ctx.getSessionId();
        }
        try {
            io.agentscope.core.agent.Agent agent = event.getAgent();
            if (agent != null && agent.getAgentState() != null) {
                String sid = agent.getAgentState().getSessionId();
                if (sid != null && !sid.isBlank()) {
                    return sid;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** SHA-256 over the serialized args, capped at {@value HASH_INPUT_CHARS} chars. */
    static String argsHash(Map<String, Object> input) {
        try {
            String json = MAPPER.writeValueAsString(input == null ? Map.of() : input);
            String capped = json.length() > HASH_INPUT_CHARS
                    ? json.substring(0, HASH_INPUT_CHARS) : json;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(capped.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return String.valueOf(System.identityHashCode(input));
        }
    }
}
