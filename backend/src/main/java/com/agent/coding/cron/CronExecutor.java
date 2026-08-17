package com.agent.coding.cron;

import com.agent.coding.ChatService;
import com.agent.coding.SettingsService;
import com.agent.coding.agent.AgentStore;
import com.agent.coding.inbox.InboxStore;
import com.agent.coding.inbox.InboxTraceStore;
import com.agent.coding.service.ModelRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes a single cron job once..
 *
 * <p>task_type "text": deliver the fixed text to the target session (via the
 * console chat store). task_type "agent": run the configured agent with the
 * request payload through {@link HarnessAgent}, record an execution trace and
 * deliver the final message. Inbox events are appended when
 * {@code save_result_to_inbox} is set, mirroring manager._execute_once.
 */
@Component
public class CronExecutor {

    private static final Logger log = LoggerFactory.getLogger(CronExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYS_PROMPT =
            "你是一个专业的编码助手。工具包括: read_file/write_file/edit_file(读写编辑), "
            + "search_code/find_symbol/list_directory(搜索), execute_command(执行命令), "
            + "git_status/git_diff/git_branch/git_commit/git_add/git_log(Git操作)。回答简洁专业。";

    private final ModelRoutingService modelRouting;
    private final ChatService chatService;
    private final Toolkit toolkit;
    private final SettingsService settingsService;
    private final InboxStore inboxStore;

    public CronExecutor(ModelRoutingService modelRouting,
                        ChatService chatService,
                        Toolkit toolkit,
                        SettingsService settingsService,
                        InboxStore inboxStore) {
        this.modelRouting = modelRouting;
        this.chatService = chatService;
        this.toolkit = toolkit;
        this.settingsService = settingsService;
        this.inboxStore = inboxStore;
    }

    public record ExecutionResult(String taskType, String runId, String deliveryStatus,
                                  String deliveryError, String finalText) {}

    /** Expose the inbox store for inbox-event appends from the manager. */
    public InboxStore inboxStore() {
        return inboxStore;
    }

    /** Execute one job once. Returns an execution result; never throws for
     *  delivery failures (they are reported in the result). */
    @SuppressWarnings("unchecked")
    public ExecutionResult execute(Map<String, Object> job, String agentId) {
        Map<String, Object> dispatch = CronModels.dispatch(job);
        Map<String, Object> target = CronModels.target(dispatch);
        String targetChannel = str(dispatch.get("channel"), "console");
        String targetUserId = str(target.get("user_id"), "");
        String targetSessionId = str(target.get("session_id"), "");
        String taskType = str(job.get("task_type"), "agent");
        Map<String, Object> runtime = CronModels.runtime(job);
        Map<String, Object> schedule = CronModels.schedule(job);
        boolean silent = Boolean.TRUE.equals(dispatch.get("silent"));
        String mode = str(dispatch.get("mode"), "stream");
        String jobId = str(job.get("id"), "");
        String jobName = str(job.get("name"), "");

        if ("text".equals(taskType)) {
            String text = str(job.get("text"), "");
            if (text.isBlank()) {
                return new ExecutionResult("text", null, "failed", "text is empty", "");
            }
            String error = null;
            try {
                deliverText(targetUserId, targetSessionId, text, agentId);
            } catch (Exception e) {
                error = e.getMessage();
                log.warn("cron text delivery failed: job_id={} error={}", jobId, error);
            }
            return new ExecutionResult("text", null,
                    error == null ? "success" : "failed", error, text.trim());
        }

        // ---- agent task ----
        Map<String, Object> request = CronModels.request(job);
        if (request == null) {
            return new ExecutionResult("agent", null, "failed", "request is missing", null);
        }
        String runId = UUID.randomUUID().toString();
        Map<String, Object> traceMeta = new LinkedHashMap<>();
        traceMeta.put("job_id", jobId);
        traceMeta.put("job_name", jobName);
        traceMeta.put("task_type", "agent");
        traceMeta.put("dispatch_channel", targetChannel);
        traceMeta.put("target_user_id", targetUserId);
        traceMeta.put("target_session_id", targetSessionId);
        traceMeta.put("silent", silent);
        try {
            InboxTraceStore.createTrace(runId, traceMeta);
        } catch (Exception e) {
            log.warn("cron: failed to create trace for job {}", jobId, e);
        }

        String sessionId;
        Boolean shareSession = (Boolean) runtime.getOrDefault("share_session", true);
        if (Boolean.TRUE.equals(shareSession)) {
            sessionId = targetSessionId.isBlank() ? "cron:" + jobId : targetSessionId;
        } else {
            sessionId = targetSessionId.isBlank()
                    ? "cron:" + jobId
                    : targetSessionId + ":cron:" + jobId;
        }
        String effectiveUserId = targetUserId.isBlank() ? "cron" : targetUserId;

        String deliveryError = null;
        boolean finalNoContent = false;
        String finalText = null;
        try {
            // Register a chat so the session appears in the frontend list.
            String chatId = null;
            try {
                var chat = chatService.getOrCreateBySession(agentId, sessionId, jobName);
                chatId = chat.getId();
            } catch (Exception e) {
                log.debug("cron: failed to register chat spec for job {}", jobId);
            }

            HarnessAgent agent = resolveAgent(agentId, targetChannel, sessionId, effectiveUserId);
            String prompt = extractPrompt(request);

            List<Map<String, Object>> completed = new ArrayList<>();
            agent.streamEvents(new UserMessage(prompt),
                    RuntimeContext.builder()
                        .sessionId(sessionId)
                        .userId(effectiveUserId)
                        .build())
                .blockLast();

            // Collect the final assistant text from the session messages.
            if (chatId != null) {
                var messages = chatService.getMessages(chatId);
                for (var msg : messages) {
                    if ("assistant".equals(msg.getRole())) {
                        String content = msg.getContent();
                        if (content != null && !content.isBlank()) {
                            finalText = content;
                        }
                    }
                }
            }
            if (finalText == null) {
                finalText = "Agent cron task finished successfully.";
            }
            if ("final".equals(mode) && !silent && finalNoContent) {
                deliveryError = "no completed message in stream";
            }
        } catch (Exception e) {
            log.warn("cron _execute_once: job_id={} status=error error={}", jobId, e.getMessage());
            deliveryError = String.valueOf(e.getMessage());
            try {
                InboxTraceStore.finalizeTrace(runId, "error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        } finally {
            try {
                InboxTraceStore.finalizeTrace(runId, deliveryError == null ? "success" : "error",
                        deliveryError);
            } catch (Exception ignored) {
            }
        }

        String deliveryStatus;
        if (silent) {
            deliveryStatus = "suppressed";
        } else if (deliveryError != null) {
            deliveryStatus = "failed";
        } else if (finalNoContent) {
            deliveryStatus = "no_content";
        } else {
            deliveryStatus = "success";
        }
        return new ExecutionResult("agent", runId, deliveryStatus, deliveryError, finalText);
    }

    private void deliverText(String userId, String sessionId, String text, String agentId) {
        String effectiveSession = sessionId.isBlank() ? "cron" : sessionId;
        var chat = chatService.getOrCreateBySession(agentId, effectiveSession, text);
        List<Map<String, String>> msgs = new ArrayList<>();
        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        // Content must be a JSON array of text blocks (same format the chat
        // stream saves via saveConsoleMessages) so the frontend vendor
        // DefaultResponseRender (data.content.map) can render it. A plain
        // string crashes the chat page.
        String contentJson;
        try {
            contentJson = MAPPER.writeValueAsString(
                    List.of(Map.of("type", "text", "text", text)));
        } catch (Exception e) {
            contentJson = "[]";
        }
        msg.put("content", contentJson);
        msgs.add(msg);
        chatService.saveMessages(chat.getId(), msgs);
    }

    private HarnessAgent resolveAgent(String agentId, String channel, String sessionId, String userId) {
        Path wsPath;
        try {
            wsPath = AgentStore.workspaceDirForAgent(agentId);
        } catch (Exception e) {
            wsPath = Path.of(System.getProperty("user.dir"));
        }
        String agentName = "majo";
        var profile = AgentStore.getProfile(agentId);
        if (profile != null && profile.get("name") != null) {
            agentName = profile.get("name").toString();
        }
        return HarnessAgent.builder()
            .name(agentName)
            .sysPrompt(SYS_PROMPT)
            .model(createModel(agentId))
            .toolkit(toolkit)
            .workspace(wsPath)
            .build();
    }

    private com.agent.coding.service.ModelRoutingService.ModelSlot effectiveSlot(String agentId) {
        try {
            return modelRouting.resolveEffectiveModel(agentId);
        } catch (Exception e) {
            return null;
        }
    }

    private io.agentscope.extensions.model.openai.OpenAIChatModel createModel(String agentId) {
        var slot = effectiveSlot(agentId);
        if (slot != null && slot.hasBoth()) {
            return modelRouting.buildOpenAIChatModel(slot.providerId(), slot.modelId());
        }
        return io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
            .apiKey(settingsService.getApiKey())
            .baseUrl(settingsService.getBaseUrl())
            .modelName(settingsService.getModelName())
            .build();
    }

    @SuppressWarnings("unchecked")
    private String extractPrompt(Map<String, Object> request) {
        Object input = request.get("input");
        if (input instanceof String s && !s.isBlank()) return s;
        if (input instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> msg) {
                Object content = msg.get("content");
                if (content instanceof String s && !s.isBlank()) return s;
                if (content instanceof List<?> parts && !parts.isEmpty()) {
                    Object part = parts.get(0);
                    if (part instanceof Map<?, ?> p) {
                        Object text = p.get("text");
                        if (text instanceof String s) return s;
                    }
                }
            }
        }
        return "";
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }
}
