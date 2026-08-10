package com.agent.coding.controller;

import com.agent.coding.ChatService;
import com.agent.coding.SettingsService;
import com.agent.coding.WorkspaceContext;
import com.agent.coding.agent.AgentStore;
import com.agent.coding.dto.*;
import com.agent.coding.entity.ChatEntity;
import com.agent.coding.entity.TokenUsageEntity;
import com.agent.coding.inbox.InboxStore;
import com.agent.coding.inbox.InboxTraceStore;
import com.agent.coding.repository.TokenUsageRepository;
import com.agent.coding.service.ModelRoutingService;
import com.agent.coding.service.TaskTracker;
import com.agent.coding.skill.SkillStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ConsoleController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleController.class);
    private static final String SYS_PROMPT = "你是一个专业的编码助手。工具包括: read_file/write_file/edit_file(读写编辑), search_code/find_symbol/list_directory(搜索), execute_command(执行命令), git_status/git_diff/git_branch/git_commit/git_add/git_log(Git操作)。回答简洁专业。";
    private static final Path DEFAULT_WORKSPACE = com.agent.coding.skill.SkillStore.WORKING_DIR;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelRoutingService modelRouting;
    private final TaskTracker taskTracker;
    private final ChatService chatService;
    private final Toolkit toolkit;
    private final Set<String> implementedToolNames;
    private final TokenUsageRepository tokenUsageRepo;
    private final SettingsService settingsService;
    private final InboxStore inboxStore;
    private final com.agent.coding.approval.ApprovalStore approvalStore;

    public ConsoleController(ModelRoutingService modelRouting, TaskTracker taskTracker,
                              ChatService chatService, Toolkit toolkit,
                              Set<String> implementedToolNames,
                              TokenUsageRepository tokenUsageRepo,
                              SettingsService settingsService,
                              InboxStore inboxStore,
                              com.agent.coding.approval.ApprovalStore approvalStore) {
        this.modelRouting = modelRouting;
        this.taskTracker = taskTracker;
        this.chatService = chatService;
        this.toolkit = toolkit;
        this.implementedToolNames = implementedToolNames;
        this.tokenUsageRepo = tokenUsageRepo;
        this.settingsService = settingsService;
        this.inboxStore = inboxStore;
        this.approvalStore = approvalStore;
    }

    @PostMapping(value = "/console/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> consoleChat(@RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        String prompt = extractPrompt(body);
        if (prompt.isBlank()) {
            return Flux.just(event("error", "prompt is required"));
        }

        String sessionId = Objects.toString(body.getOrDefault("session_id", UUID.randomUUID().toString()), UUID.randomUUID().toString());
        String workspace = Objects.toString(body.getOrDefault("workspace", ""), "");
        String rawAgentId = request.getHeader("X-Agent-Id");
        final String agentId = (rawAgentId != null && !rawAgentId.isBlank())
            ? rawAgentId : Objects.toString(body.get("agent_id"), "default");

        WorkspaceContext.set(workspace);

        // Find or create chat by session_id (qwenpaw: get_or_create_chat)
        var chatEntity = chatService.getOrCreateBySession(agentId, sessionId, prompt);
        final String chatId = chatEntity.getId();
        final String placeholderTitle = chatEntity.getTitle();
        final String firstUserText = prompt;
        chatService.setStatus(chatId, "running");
        taskTracker.setRunning(chatId);

        HarnessAgent agent = resolveAgent(workspace, agentId);

        var ctx = RuntimeContext.builder()
            .sessionId(sessionId)
            .userId(Objects.toString(body.getOrDefault("user_id", "web-user"), "web-user"))
            .build();

        String responseId = "response_" + UUID.randomUUID().toString().replace("-", "");
        var seq = new java.util.concurrent.atomic.AtomicLong(1);
        var thinker = new StringBuilder();
        var texter = new StringBuilder();
        var thinkingMsgId = new String[] { null };
        var textMsgId = new String[] { null };
        var usageHolder = new Object() { int inputTokens, outputTokens; double timeSec; };
        List<Map<String, Object>> completedMessages = new ArrayList<>();

        return Flux.concat(
            Flux.just(
                sseEvent(qwenResponseCreated(responseId, sessionId, seq)),
                sseEvent(qwenResponseInProgress(responseId, sessionId, seq))
            ),
            agent.streamEvents(new UserMessage(prompt), ctx)
                .handle((event, sink) -> {
                    String type = event.getClass().getSimpleName();
                    try {
                        switch (type) {
                            case "ThinkingBlockStartEvent": {
                                thinkingMsgId[0] = "msg_" + UUID.randomUUID().toString().replace("-", "");
                                thinker.setLength(0);
                                sink.next(sseEvent(QwenEvents.reasoningInProgress(thinkingMsgId[0], seq)));
                                return;
                            }
                            case "ThinkingBlockDeltaEvent": {
                                String delta = event.getClass().getMethod("getDelta").invoke(event).toString();
                                thinker.append(delta);
                                sink.next(sseEvent(QwenEvents.contentDelta(thinkingMsgId[0], delta, seq, thinker.toString())));
                                return;
                            }
                            case "ThinkingBlockEndEvent": {
                                String full = thinker.toString();
                                var msg = new LinkedHashMap<String, Object>();
                                msg.put("id", thinkingMsgId[0]);
                                msg.put("type", "reasoning");
                                msg.put("role", "assistant");
                                msg.put("content", List.of(Map.of("type", "text", "text", full)));
                                msg.put("status", "completed");
                                completedMessages.add(msg);
                                sink.next(sseEvent(QwenEvents.contentFinal(thinkingMsgId[0], full, seq)));
                                sink.next(sseEvent(QwenEvents.reasoningCompleted(thinkingMsgId[0], full, seq)));
                                return;
                            }
                            case "TextBlockStartEvent": {
                                textMsgId[0] = "msg_" + UUID.randomUUID().toString().replace("-", "");
                                texter.setLength(0);
                                sink.next(sseEvent(QwenEvents.textMessageInProgress(textMsgId[0], seq)));
                                return;
                            }
                            case "TextBlockDeltaEvent": {
                                String delta = event.getClass().getMethod("getDelta").invoke(event).toString();
                                texter.append(delta);
                                sink.next(sseEvent(QwenEvents.contentDelta(textMsgId[0], delta, seq, texter.toString())));
                                return;
                            }
                            case "TextBlockEndEvent": {
                                String full = texter.toString();
                                var msg = new LinkedHashMap<String, Object>();
                                msg.put("id", textMsgId[0]);
                                msg.put("type", "message");
                                msg.put("role", "assistant");
                                msg.put("content", List.of(Map.of("type", "text", "text", full)));
                                msg.put("status", "completed");
                                completedMessages.add(msg);
                                sink.next(sseEvent(QwenEvents.contentFinal(textMsgId[0], full, seq)));
                                sink.next(sseEvent(QwenEvents.textMessageCompleted(textMsgId[0], full, seq)));
                                return;
                            }
                            case "ModelCallEndEvent": {
                                try {
                                    var usageMethod = event.getClass().getMethod("getUsage");
                                    var usage = usageMethod.invoke(event);
                                    if (usage != null) {
                                        var u = (Map<?, ?>) usage;
                                        usageHolder.inputTokens = intVal(u, "inputTokens");
                                        usageHolder.outputTokens = intVal(u, "outputTokens");
                                        usageHolder.timeSec = doubleVal(u, "time");
                                    }
                                } catch (Exception e) {
                                    // Fallback: try ObjectMapper serialization
                                    try {
                                        var tree = MAPPER.valueToTree(event);
                                        var usage = tree.get("usage");
                                        if (usage != null) {
                                            usageHolder.inputTokens = usage.get("inputTokens").asInt();
                                            usageHolder.outputTokens = usage.get("outputTokens").asInt();
                                            if (usage.has("time")) usageHolder.timeSec = usage.get("time").asDouble();
                                        }
                                    } catch (Exception ignored) {}
                                }
                                return;
                            }
                            default:
                                return;
                        }
                    } catch (Exception e) {
                        return;
                    }
                }),
            Flux.defer(() -> Flux.just(
                sseEvent(QwenEvents.responseCompleted(responseId, sessionId, seq,
                    usageHolder.inputTokens, usageHolder.outputTokens, completedMessages)),
                sseEvent(QwenEvents.turnUsage(sessionId, seq, usageHolder.inputTokens, usageHolder.outputTokens))
            ))
        ).doFinally(sig -> {
            taskTracker.setDone(chatId);
            saveConsoleMessages(chatId, prompt, completedMessages);
            chatService.setStatus(chatId, "idle");
            // Save token usage
            if (usageHolder.inputTokens > 0 || usageHolder.outputTokens > 0) {
                var slot = modelRouting.resolveEffectiveModel(agentId);
                String today = LocalDate.now().toString();
                tokenUsageRepo.save(new TokenUsageEntity(today,
                    slot.providerId() != null ? slot.providerId() : "",
                    slot.modelId() != null ? slot.modelId() : "",
                    usageHolder.inputTokens, usageHolder.outputTokens));
            }
            // Background LLM title generation (qwenpaw: generate_and_update_title)
            if (firstUserText != null && !firstUserText.isBlank()
                    && !"New Chat".equals(placeholderTitle)) {
                new Thread(() -> generateTitle(chatId, placeholderTitle, firstUserText, agentId)).start();
            }
            WorkspaceContext.clear();
        });
    }

    @GetMapping("/agent-stats")
    public AgentStatsSummary agentStats(
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date) {
        var now = LocalDate.now();
        var end = end_date != null && !end_date.isBlank() ? LocalDate.parse(end_date) : now;
        var start = start_date != null && !start_date.isBlank() ? LocalDate.parse(start_date) : end.minusDays(30);
        if (start.isAfter(end)) { var tmp = start; start = end; end = tmp; }

        var summary = new AgentStatsSummary();
        summary.setStartDate(start.toString());
        summary.setEndDate(end.toString());

        // By-date aggregation
        var days = new java.util.LinkedHashMap<String, AgentStatsSummary.DailyStats>();
        for (var d = start; !d.isAfter(end); d = d.plusDays(1)) {
            days.put(d.toString(), new AgentStatsSummary.DailyStats(d.toString()));
        }

        // Chats by creation date
        var allChats = chatService.list(null, null, null);
        for (var c : allChats) {
            if (c.getCreatedAt() != null) {
                var d = c.getCreatedAt().toLocalDate();
                var ds = days.get(d.toString());
                if (ds != null) ds.setChats(ds.getChats() + 1);
            }
        }

        // Messages aggregation from DB
        var channelMap = new java.util.LinkedHashMap<String, AgentStatsSummary.ChannelStats>();
        int totalUser = 0, totalAsst = 0, totalTool = 0;
        var activeSessionSet = new java.util.HashSet<String>();

        for (var c : allChats) {
            var msgs = chatService.getMessages(c.getId());
            String ch = c.getChannel() != null ? c.getChannel() : "console";
            var cs = channelMap.computeIfAbsent(ch, AgentStatsSummary.ChannelStats::new);
            for (var m : msgs) {
                if (m.getCreatedAt() == null) continue;
                var d = m.getCreatedAt().toLocalDate();
                var ds = days.get(d.toString());
                if (ds == null) continue;
                if ("user".equals(m.getRole())) {
                    ds.setUserMessages(ds.getUserMessages() + 1);
                    totalUser++;
                    cs.setUserMessages(cs.getUserMessages() + 1);
                    activeSessionSet.add(c.getId() + ":" + d);
                } else if ("assistant".equals(m.getRole())) {
                    ds.setAssistantMessages(ds.getAssistantMessages() + 1);
                    totalAsst++;
                    cs.setAssistantMessages(cs.getAssistantMessages() + 1);
                }
                if (m.getToolCalls() != null && !m.getToolCalls().isBlank()) {
                    ds.setToolCalls(ds.getToolCalls() + 1);
                    totalTool++;
                }
                ds.setTotalMessages(ds.getUserMessages() + ds.getAssistantMessages());
            }
            cs.setTotalMessages(cs.getUserMessages() + cs.getAssistantMessages());
            cs.setSessionCount(cs.getSessionCount() + 1);
        }

        for (var ds : days.values()) {
            ds.setActiveSessions(activeSessionSet.stream()
                .filter(s -> s.endsWith(":" + ds.getDate())).mapToInt(x -> 1).sum());
        }

        summary.setByDate(new ArrayList<>(days.values()));
        summary.setChannelStats(new ArrayList<>(channelMap.values()));
        summary.setTotalUserMessages(totalUser);
        summary.setTotalAssistantMessages(totalAsst);
        summary.setTotalMessages(totalUser + totalAsst);
        summary.setTotalToolCalls(totalTool);
        summary.setTotalActiveSessions((int) activeSessionSet.stream().map(s -> s.split(":")[0]).distinct().count());

        return summary;
    }

    @GetMapping("/version")
    public VersionResponse version() {
        return new VersionResponse("0.1.0");
    }

    @GetMapping("/healthz")
    public StatusResponse healthz() {
        return StatusResponse.ok();
    }

    @PostMapping("/console/chat/stop")
    public StatusResponse stopChat() {
        return StatusResponse.ok();
    }

    @GetMapping("/loops/status")
    public Map<String, Object> loopStatus(
            @RequestParam(required = false) String chat_id,
            @RequestParam(required = false) String session_id) {

        String executionPhase = "awaiting_user";
        String resolvedSessionId = session_id;

        if (chat_id != null && !chat_id.isBlank()) {
            try {
                ChatEntity chat = chatService.getChat(chat_id);
                if (chat != null) {
                    resolvedSessionId = chat.getSessionId();
                    String runStatus = taskTracker.getStatus(chat.getId());
                    if ("running".equals(runStatus)) {
                        executionPhase = "running";
                    }
                } else if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
                    return idleStatus();
                }
            } catch (Exception ignored) {
                if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
                    return idleStatus();
                }
            }
        }

        if (resolvedSessionId == null || resolvedSessionId.isBlank()) {
            return idleStatus();
        }

        return idleStatus();
    }

    private static Map<String, Object> idleStatus() {
        var m = new LinkedHashMap<String, Object>();
        m.put("state", "idle");
        m.put("mode", null);
        return m;
    }

    // GET /tools and its tool() helper moved to ToolsController.

    @GetMapping("/token-usage")
    public TokenUsageSummary tokenUsage(
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider) {
        var now = LocalDate.now().toString();
        var end = end_date != null && !end_date.isBlank() ? end_date : now;
        var start = start_date != null && !start_date.isBlank() ? start_date : "1970-01-01";

        var records = tokenUsageRepo.findByDateRange(start, end);
        var summary = new TokenUsageSummary();
        var byModel = new java.util.LinkedHashMap<String, TokenUsageStats>();
        var byDate = new java.util.LinkedHashMap<String, TokenUsageStats>();

        long totalPrompt = 0, totalCompl = 0, totalCalls = 0;
        for (var r : records) {
            if (model != null && !model.isBlank() && !model.equals(r.getModel())) continue;
            if (provider != null && !provider.isBlank() && !provider.equals(r.getProviderId())) continue;

            totalPrompt += r.getPromptTokens();
            totalCompl += r.getCompletionTokens();
            totalCalls += r.getCallCount();

            String modelKey = r.getProviderId().isBlank() ? r.getModel()
                : r.getProviderId() + ":" + r.getModel();
            var bm = byModel.computeIfAbsent(modelKey, k -> {
                var s = new TokenUsageStats();
                s.setProviderId(r.getProviderId());
                s.setModel(r.getModel());
                return s;
            });
            bm.setPromptTokens(bm.getPromptTokens() + r.getPromptTokens());
            bm.setCompletionTokens(bm.getCompletionTokens() + r.getCompletionTokens());
            bm.setCallCount(bm.getCallCount() + r.getCallCount());

            var bd = byDate.computeIfAbsent(r.getUsageDate(), k -> new TokenUsageStats());
            bd.setPromptTokens(bd.getPromptTokens() + r.getPromptTokens());
            bd.setCompletionTokens(bd.getCompletionTokens() + r.getCompletionTokens());
            bd.setCallCount(bd.getCallCount() + r.getCallCount());
        }

        summary.setTotalPromptTokens(totalPrompt);
        summary.setTotalCompletionTokens(totalCompl);
        summary.setTotalCalls(totalCalls);
        summary.setByModel(byModel);
        summary.setByDate(byDate);
        return summary;
    }

    @GetMapping("/token-usage/details")
    public List<TokenUsageRecord> tokenUsageDetails(
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider) {
        var now = LocalDate.now().toString();
        var end = end_date != null && !end_date.isBlank() ? end_date : now;
        var start = start_date != null && !start_date.isBlank() ? start_date : "1970-01-01";

        return tokenUsageRepo.findByDateRange(start, end).stream()
            .filter(r -> model == null || model.isBlank() || model.equals(r.getModel()))
            .filter(r -> provider == null || provider.isBlank() || provider.equals(r.getProviderId()))
            .map(r -> new TokenUsageRecord(r.getUsageDate(), r.getProviderId(), r.getModel(),
                    r.getPromptTokens(), r.getCompletionTokens(), r.getCallCount()))
            .toList();
    }

    @PostMapping("/console/chat/task")
    public Map<String, String> createChatTask() {
        return Map.of("task_id", UUID.randomUUID().toString());
    }

    @GetMapping("/console/chat/task/{task_id}")
    public Map<String, String> getChatTask(@PathVariable String task_id) {
        return Map.of("task_id", task_id, "status", "completed");
    }

    @GetMapping("/console/inbox/events")
    public Map<String, Object> inboxEvents(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String source_type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String agent_id,
            @RequestParam(defaultValue = "false") boolean unread_only) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return Map.of("events", inboxStore.listEvents(
                safeLimit, offset, source_type, status, agent_id, unread_only));
    }

    public record MarkInboxReadRequest(List<String> event_ids, boolean all) {}

    @PostMapping("/console/inbox/read")
    public Map<String, Object> markInboxRead(@RequestBody(required = false) MarkInboxReadRequest body) {
        boolean all = body != null && body.all;
        List<String> eventIds = body == null ? List.of()
                : (body.event_ids() == null ? List.of() : body.event_ids());
        int updated = all ? inboxStore.markAllRead() : inboxStore.markRead(eventIds);
        return Map.of("updated", updated);
    }

    @DeleteMapping("/console/inbox/events/{event_id}")
    public org.springframework.http.ResponseEntity<?> deleteInboxEvent(@PathVariable String event_id) {
        var result = inboxStore.deleteEvent(event_id);
        if (!result.deleted()) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "event not found"));
        }
        boolean traceDeleted = false;
        if (result.runId() != null && !result.runId().isBlank() && !result.runIdStillReferenced()) {
            traceDeleted = InboxTraceStore.deleteTrace(result.runId());
        }
        return org.springframework.http.ResponseEntity.ok(Map.of(
                "deleted", true,
                "trace_deleted", traceDeleted,
                "run_id", Objects.toString(result.runId(), "")));
    }

    @GetMapping("/console/inbox/traces/{run_id}")
    public Map<String, Object> getInboxTrace(@PathVariable String run_id) {
        Map<String, Object> trace = InboxTraceStore.getTrace(run_id);
        if (trace == null) {
            throw new InboxNotFoundException("trace not found");
        }
        return trace;
    }

    /** 404 + {"detail": ...} body, matching FastAPI HTTPException so the
     *  frontend parseErrorDetail can read it. */
    public static class InboxNotFoundException extends RuntimeException {
        public InboxNotFoundException(String detail) { super(detail); }
        public String getDetail() { return getMessage(); }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(InboxNotFoundException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handleInboxNotFound(InboxNotFoundException e) {
        return org.springframework.http.ResponseEntity
                .status(org.springframework.http.HttpStatus.NOT_FOUND)
                .body(Map.of("detail", e.getDetail()));
    }

    @GetMapping("/console/push-messages")
    public Map<String, Object> pushMessages() {
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (com.agent.coding.approval.ApprovalStore.ApprovalRequest req : approvalStore.listPending()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("request_id", req.requestId);
            a.put("session_id", req.sessionId);
            a.put("root_session_id", req.rootSessionId);
            a.put("owner_agent_id", req.agentId);
            a.put("agent_id", req.agentId);
            a.put("tool_name", req.toolName);
            a.put("tool_display_name", req.toolDisplayName);
            a.put("severity", req.severity);
            a.put("created_at", req.createdAt);
            a.put("timeout_seconds", req.timeoutSeconds);
            approvals.add(a);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", List.of());
        result.put("pending_approvals", approvals);
        return result;
    }

    @PostMapping("/console/upload")
    public Map<String, Object> consoleUpload() {
        return Map.of("url", "", "file_name", "");
    }

    @PostMapping("/harnesses/{provider_id}/login")
    public StatusResponse harnessLogin(@PathVariable String provider_id) {
        return StatusResponse.ok();
    }

    @PostMapping("/harnesses/{provider_id}/logout")
    public StatusResponse harnessLogout(@PathVariable String provider_id) {
        return StatusResponse.ok();
    }

    @PostMapping("/harnesses/{provider_id}/status")
    public StatusResponse harnessStatus(@PathVariable String provider_id) {
        return StatusResponse.ok();
    }

    /**
     * Harness model catalog for the per-agent model picker
     * (qwenpaw /harnesses/{provider_id}/models). Returns the models available
     * to the given backend provider. For majo, the catalog is derived from
     * all configured providers/models (the harness backend routes through the
     * same model routing), so the picker has real options to choose from.
     */
    @GetMapping("/harnesses/{provider_id}/models")
    public Map<String, Object> harnessModels(@PathVariable String provider_id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("models", modelRouting.listHarnessModels());
        return resp;
    }

    @PostMapping("/fork/agent")
    public Map<String, String> forkAgent() { return Map.of("id", UUID.randomUUID().toString()); }

    @GetMapping("/")
    public Map<String, String> root() { return Map.of("status", "ok"); }

    @SuppressWarnings("unchecked")
    private String extractPrompt(Map<String, Object> body) {
        Object input = body.get("input");
        if (input instanceof String s && !s.isBlank()) return s;
        if (input instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> msg) {
                Object content = msg.get("content");
                if (content instanceof String s) return s;
                if (content instanceof List<?> parts && !parts.isEmpty()) {
                    Object part = parts.get(0);
                    if (part instanceof Map<?, ?> p) {
                        return Objects.toString(p.get("text"), "");
                    }
                }
            }
        }
        return "";
    }

    private HarnessAgent resolveAgent(String workspace, String agentId) {
        Path wsPath;
        String agentName = "majo";

        if (!workspace.isBlank()) {
            wsPath = Paths.get(workspace).toAbsolutePath().normalize();
            if (!Files.isDirectory(wsPath)) {
                wsPath = resolveAgentWorkspace(agentId);
            }
        } else {
            wsPath = resolveAgentWorkspace(agentId);
        }

        var profile = AgentStore.getProfile(agentId);
        if (profile != null && profile.get("name") != null) {
            agentName = profile.get("name").toString();
        }
        log.info("Resolved agent '{}' workspace: {}, name: {}", agentId, wsPath, agentName);

        return HarnessAgent.builder()
            .name(agentName)
            .sysPrompt(SYS_PROMPT)
            .model(createModel(agentId))
            .toolkit(toolkit)
            .workspace(wsPath)
            .build();
    }

    private Path resolveAgentWorkspace(String agentId) {
        try {
            return AgentStore.workspaceDirForAgent(agentId);
        } catch (Exception e) {
            log.warn("Failed to resolve workspace for agent '{}', using default: {}", agentId, e.getMessage());
            return DEFAULT_WORKSPACE;
        }
    }

    private OpenAIChatModel createModel(String agentId) {
        var slot = modelRouting.resolveEffectiveModel(agentId);
        if (!slot.hasBoth()) {
            log.warn("No active model configured for agent '{}', using gpt-4o-mini fallback", agentId);
            return OpenAIChatModel.builder()
                .apiKey("")
                .baseUrl("https://api.openai.com/v1")
                .modelName("gpt-4o-mini")
                .build();
        }
        log.info("Effective model for agent '{}': {}/{}", agentId, slot.providerId(), slot.modelId());
        return modelRouting.buildOpenAIChatModel(slot.providerId(), slot.modelId());
    }

    private void saveConsoleMessages(String chatId, String userPrompt,
                                       List<Map<String, Object>> completedMessages) {
        if (chatId == null || chatId.isBlank()) return;
        List<Map<String, String>> msgs = new ArrayList<>();
        // User message with content array
        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        String userContent;
        try {
            userContent = MAPPER.writeValueAsString(
                userPrompt != null && !userPrompt.isBlank()
                    ? List.of(Map.of("type", "text", "text", userPrompt))
                    : List.of());
        } catch (Exception e) {
            userContent = "[]";
        }
        userMsg.put("content", userContent);
        msgs.add(userMsg);
        // Assistant messages from completed blocks
        for (var cm : completedMessages) {
            Map<String, String> asstMsg = new LinkedHashMap<>();
            asstMsg.put("role", "assistant");
            @SuppressWarnings("unchecked")
            var contentList = (List<Map<String, Object>>) cm.getOrDefault("content", List.of());
            String contentJson;
            try {
                contentJson = MAPPER.writeValueAsString(contentList);
            } catch (Exception e) {
                contentJson = "[]";
            }
            asstMsg.put("content", contentJson);
            String messageType = Objects.toString(cm.get("type"), "message");
            // Store reasoning as thinking for display
            if ("reasoning".equals(messageType)) {
                StringBuilder sb = new StringBuilder();
                for (var c : contentList) {
                    Object text = c.get("text");
                    if (text != null) sb.append(text.toString());
                }
                if (sb.length() > 0) {
                    asstMsg.put("thinking", sb.toString());
                }
            }
            msgs.add(asstMsg);
        }
        chatService.saveMessages(chatId, msgs);
    }

    private void generateTitle(String chatId, String placeholderTitle, String userMessage, String agentId) {
        try {
            var slot = modelRouting.resolveEffectiveModel(agentId);
            if (!slot.hasBoth()) return;
            var model = modelRouting.buildOpenAIChatModel(slot.providerId(), slot.modelId());
            String systemPrompt = "You generate short titles for chat sessions. Given the first user "
                + "message, reply with a concise title (at most 6 words, no quotes, no "
                + "trailing punctuation, same language as the message) that captures the "
                + "topic. Reply with the title only.";
            String prompt = userMessage.length() > 500 ? userMessage.substring(0, 500) : userMessage;
            var messages = List.of(
                io.agentscope.core.message.Msg.builder()
                    .name("system").role(io.agentscope.core.message.MsgRole.SYSTEM)
                    .content(io.agentscope.core.message.TextBlock.builder().text(systemPrompt).build())
                    .build(),
                io.agentscope.core.message.Msg.builder()
                    .name("user").role(io.agentscope.core.message.MsgRole.USER)
                    .content(io.agentscope.core.message.TextBlock.builder().text(prompt).build())
                    .build()
            );
            var sb = new StringBuilder();
            model.stream(messages, null, null)
                .doOnNext(r -> {
                    String text = extractMsgText(r);
                    if (text != null && !text.isEmpty() && text.length() > sb.length()) {
                        sb.setLength(0);
                        sb.append(text);
                    }
                })
                .blockLast();
            String raw = sb.toString();
            String title = _cleanTitle(raw);
            if (!title.isEmpty()) {
                chatService.patchChatIfNameMatches(chatId, placeholderTitle, title);
            }
        } catch (Exception e) {
            log.warn("Background title generation failed for chat {}: {}", chatId, e.getMessage());
        }
    }

    private static String extractMsgText(Object response) {
        if (response == null) return null;
        try {
            var m = response.getClass().getMethod("getTextContent");
            return (String) m.invoke(response);
        } catch (Exception e) {
            return null;
        }
    }

    private static String _cleanTitle(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String title = raw.trim().split("\\n")[0].trim();
        title = title.replaceAll("^[\"'`\"\"'']+|[\"'`\"\"'']+$", "");
        while (!title.isEmpty() && ". ,;:!?".indexOf(title.charAt(title.length() - 1)) >= 0) {
            title = title.substring(0, title.length() - 1).trim();
        }
        if (title.length() > 60) title = title.substring(0, 60).trim();
        return title;
    }

    private String event(String type, String content) {
        try {
            var node = MAPPER.createObjectNode();
            node.put("type", type);
            node.put("content", content);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"type\":\"error\"}";
        }
    }

    private static String sseEvent(String json) {
        return json;
    }

    private static String qwenResponseCreated(String responseId, String sessionId,
                                                java.util.concurrent.atomic.AtomicLong seq) {
        return toJson(jo -> {
            jo.put("id", responseId);
            jo.put("output", MAPPER.createArrayNode());
            jo.put("status", "created");
            jo.put("created_at", java.time.Instant.now().toString());
            jo.putNull("completed_at");
            jo.putNull("metadata");
            jo.put("object", "response");
            jo.put("session_id", sessionId);
            jo.put("sequence_number", seq.getAndIncrement());
        });
    }

    private static String qwenResponseInProgress(String responseId, String sessionId,
                                                   java.util.concurrent.atomic.AtomicLong seq) {
        return toJson(jo -> {
            jo.put("id", responseId);
            jo.put("output", MAPPER.createArrayNode());
            jo.put("status", "in_progress");
            jo.put("created_at", java.time.Instant.now().toString());
            jo.putNull("completed_at");
            jo.putNull("metadata");
            jo.put("object", "response");
            jo.put("session_id", sessionId);
            jo.put("sequence_number", seq.getAndIncrement());
        });
    }

    private static int intVal(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static double doubleVal(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return 0;
    }

    @FunctionalInterface
    private interface JsonFiller { void fill(com.fasterxml.jackson.databind.node.ObjectNode jo); }

    private static String toJson(JsonFiller filler) {
        try {
            var jo = MAPPER.createObjectNode();
            filler.fill(jo);
            return MAPPER.writeValueAsString(jo);
        } catch (Exception e) {
            return "{}";
        }
    }

    static class QwenEvents {
        static String reasoningInProgress(String msgId, java.util.concurrent.atomic.AtomicLong seq) {
            return toJson(jo -> {
                jo.put("id", msgId);
                jo.put("type", "reasoning");
                jo.put("role", "assistant");
                jo.put("content", MAPPER.createArrayNode());
                jo.put("status", "in_progress");
                jo.putNull("metadata");
                jo.put("name", "assistant");
                jo.put("object", "message");
                jo.put("sequence_number", seq.getAndIncrement());
            });
        }

        static String reasoningCompleted(String msgId, String fullText, java.util.concurrent.atomic.AtomicLong seq) {
            return toJson(jo -> {
                jo.put("id", msgId);
                jo.put("type", "reasoning");
                jo.put("role", "assistant");
                jo.put("content", MAPPER.createArrayNode()
                    .add(MAPPER.createObjectNode()
                        .put("type", "text")
                        .put("delta", false)
                        .put("index", 0)
                        .putNull("status")
                        .put("object", "content")
                        .put("text", fullText)));
                jo.put("status", "completed");
                jo.putNull("metadata");
                jo.put("name", "assistant");
                jo.put("object", "message");
                jo.put("sequence_number", seq.getAndIncrement());
            });
        }

        static String textMessageInProgress(String msgId, java.util.concurrent.atomic.AtomicLong seq) {
            return toJson(jo -> {
                jo.put("id", msgId);
                jo.put("type", "message");
                jo.put("role", "assistant");
                jo.put("content", MAPPER.createArrayNode());
                jo.put("status", "in_progress");
                jo.putNull("metadata");
                jo.put("name", "assistant");
                jo.put("object", "message");
                jo.put("sequence_number", seq.getAndIncrement());
            });
        }

        static String textMessageCompleted(String msgId, String fullText, java.util.concurrent.atomic.AtomicLong seq) {
            return toJson(jo -> {
                jo.put("id", msgId);
                jo.put("type", "message");
                jo.put("role", "assistant");
                jo.put("content", MAPPER.createArrayNode()
                    .add(MAPPER.createObjectNode()
                        .put("type", "text")
                        .put("delta", false)
                        .put("index", 0)
                        .putNull("status")
                        .put("object", "content")
                        .put("text", fullText)));
                jo.put("status", "completed");
                jo.putNull("metadata");
                jo.put("name", "assistant");
                jo.put("object", "message");
                jo.put("sequence_number", seq.getAndIncrement());
                var usage = MAPPER.createObjectNode();
                usage.put("input_tokens", 0);
                usage.put("output_tokens", 0);
                jo.set("usage", usage);
            });
        }

        static String contentDelta(String msgId, String delta, java.util.concurrent.atomic.AtomicLong seq,
                                    String fullSoFar) {
            return toJson(jo -> {
                jo.put("type", "text");
                jo.put("delta", true);
                jo.put("index", 0);
                jo.putNull("status");
                jo.put("object", "content");
                jo.put("msg_id", msgId);
                jo.put("text", delta);
                jo.put("sequence_number", seq.getAndIncrement());
            });
        }

        static String contentFinal(String msgId, String fullText, java.util.concurrent.atomic.AtomicLong seq) {
            return toJson(jo -> {
                jo.put("type", "text");
                jo.put("delta", false);
                jo.put("index", 0);
                jo.putNull("status");
                jo.put("object", "content");
                jo.put("msg_id", msgId);
                jo.put("text", fullText);
                jo.put("sequence_number", seq.getAndIncrement());
            });
        }

        static String responseCompleted(String responseId, String sessionId,
                                          java.util.concurrent.atomic.AtomicLong seq,
                                          int inputTokens, int outputTokens,
                                          List<Map<String, Object>> completedMessages) {
            return toJson(jo -> {
                jo.put("id", responseId);
                var output = MAPPER.createArrayNode();
                for (var msg : completedMessages) {
                    output.add(MAPPER.valueToTree(msg));
                }
                jo.set("output", output);
                jo.put("status", "completed");
                jo.put("created_at", java.time.Instant.now().toString());
                jo.put("completed_at", java.time.Instant.now().toString());
                jo.putNull("metadata");
                jo.put("object", "response");
                jo.put("session_id", sessionId);
                jo.put("sequence_number", seq.getAndIncrement());
                var u = MAPPER.createObjectNode();
                u.put("input_tokens", inputTokens);
                u.put("output_tokens", outputTokens);
                jo.set("usage", u);
            });
        }

        static String turnUsage(String sessionId, java.util.concurrent.atomic.AtomicLong seq,
                                 int inputTokens, int outputTokens) {
            return toJson(jo -> {
                jo.put("type", "turn_usage");
                jo.put("session_id", sessionId);
                var u = MAPPER.createObjectNode();
                u.put("provider_id", "");
                u.put("model_name", "");
                u.put("prompt_tokens", inputTokens);
                u.put("completion_tokens", outputTokens);
                u.put("total_tokens", inputTokens + outputTokens);
                u.put("context_size", 131072);
                u.put("compact_threshold", 0.8);
                jo.set("usage", u);
                jo.put("sequence_number", seq.getAndIncrement());
            });
        }
    }

    // ===== Debug backend logs (ported from qwenpaw console.py /debug/backend-logs) =====
    private static final int MAX_DEBUG_LOG_LINES = 1000;
    private static final int DEBUG_LOG_MAX_TAIL_BYTES = 512 * 1024;

    @GetMapping("/console/debug/backend-logs")
    public Map<String, Object> consoleBackendLogs(@RequestParam(defaultValue = "200") int lines) {
        int clamped = Math.max(20, Math.min(lines, MAX_DEBUG_LOG_LINES));
        Path logPath = SkillStore.WORKING_DIR.resolve("majo.log").toAbsolutePath().normalize();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", logPath.toString());
        result.put("lines", clamped);
        try {
            BasicFileAttributes attrs = Files.readAttributes(logPath, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                return missingLogFile(result);
            }
            result.put("exists", true);
            result.put("updated_at", attrs.lastModifiedTime().toInstant().getEpochSecond());
            result.put("size", attrs.size());
            result.put("content", tailTextFile(logPath, clamped));
        } catch (IOException e) {
            return missingLogFile(result);
        }
        return result;
    }

    @GetMapping("/agents/{agentId}/console/debug/backend-logs")
    public Map<String, Object> agentBackendLogs(@PathVariable String agentId,
                                                @RequestParam(defaultValue = "200") int lines) {
        return consoleBackendLogs(lines);
    }

    private Map<String, Object> missingLogFile(Map<String, Object> result) {
        result.put("exists", false);
        result.put("updated_at", null);
        result.put("size", 0);
        result.put("content", "");
        return result;
    }

    private String tailTextFile(Path path, int lines) {
        try {
            long size = Files.size(path);
            if (size == 0) {
                return "";
            }
            long start = Math.max(size - DEBUG_LOG_MAX_TAIL_BYTES, 0);
            int len = (int) (size - start);
            byte[] data = new byte[len];
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                raf.seek(start);
                raf.readFully(data);
            }
            String text = new String(data, StandardCharsets.UTF_8);
            String[] split = text.split("\\R", -1);
            int from = Math.max(0, split.length - lines);
            return String.join("\n", Arrays.copyOfRange(split, from, split.length));
        } catch (IOException e) {
            log.warn("Failed to read backend debug log file {}", path, e);
            return "";
        }
    }

    // ── Agent-scoped console (port of qwenpaw agent_scoped /agents/{agentId}/console) ──
    @PostMapping("/agents/{agentId}/console/chat/task")
    public Object agentChatTask(@PathVariable String agentId) { return createChatTask(); }

    @GetMapping("/agents/{agentId}/console/chat/task/{task_id}")
    public Object agentChatTaskStatus(@PathVariable String agentId, @PathVariable String task_id) {
        return getChatTask(task_id);
    }

    @GetMapping("/agents/{agentId}/console/inbox/events")
    public Object agentInboxEvents(@PathVariable String agentId,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(defaultValue = "0") int offset,
                                   @RequestParam(required = false) String source_type,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String agent_id,
                                   @RequestParam(defaultValue = "false") boolean unread_only) {
        return inboxEvents(limit, offset, source_type, status, agent_id, unread_only);
    }

    @DeleteMapping("/agents/{agentId}/console/inbox/events/{event_id}")
    public Object agentInboxEventDelete(@PathVariable String agentId, @PathVariable String event_id) {
        return deleteInboxEvent(event_id);
    }

    @PostMapping("/agents/{agentId}/console/inbox/read")
    public Object agentInboxRead(@PathVariable String agentId,
                                 @RequestBody(required = false) MarkInboxReadRequest body) {
        return markInboxRead(body);
    }

    @GetMapping("/agents/{agentId}/console/inbox/traces/{run_id}")
    public Object agentInboxTrace(@PathVariable String agentId, @PathVariable String run_id) {
        return getInboxTrace(run_id);
    }

    @GetMapping("/agents/{agentId}/console/push-messages")
    public Object agentPushMessages(@PathVariable String agentId) { return pushMessages(); }

    @PostMapping("/agents/{agentId}/console/upload")
    public Object agentUpload(@PathVariable String agentId) { return consoleUpload(); }
}
