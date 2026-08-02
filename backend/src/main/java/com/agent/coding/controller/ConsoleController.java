package com.agent.coding.controller;

import com.agent.coding.SettingsService;
import com.agent.coding.WorkspaceContext;
import com.agent.coding.dto.TokenUsageSummary;
import com.agent.coding.dto.TokenUsageRecord;
import com.agent.coding.inbox.InboxStore;
import com.agent.coding.inbox.InboxTraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ConsoleController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleController.class);
    private static final String SYS_PROMPT = "你是一个专业的编码助手。工具包括: read_file/write_file/edit_file(读写编辑), search_code/find_symbol/list_directory(搜索), execute_command(执行命令), git_status/git_diff/git_branch/git_commit/git_add/git_log(Git操作)。回答简洁专业。";
    private static final Path DEFAULT_WORKSPACE = Paths.get(System.getProperty("user.dir"));

    private final SettingsService settingsService;
    private final Toolkit toolkit;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConsoleController(SettingsService settingsService, Toolkit toolkit) {
        this.settingsService = settingsService;
        this.toolkit = toolkit;
    }

    @PostMapping(value = "/console/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> consoleChat(@RequestBody Map<String, Object> body) {
        String prompt = extractPrompt(body);
        if (prompt.isBlank()) {
            return Flux.just(event("error", "prompt is required"));
        }

        String sessionId = Objects.toString(body.getOrDefault("session_id", UUID.randomUUID().toString()), UUID.randomUUID().toString());
        String workspace = Objects.toString(body.getOrDefault("workspace", ""), "");

        WorkspaceContext.set(workspace);
        HarnessAgent agent = resolveAgent(workspace);

        var ctx = RuntimeContext.builder()
            .sessionId(sessionId)
            .userId(Objects.toString(body.getOrDefault("user_id", "web-user"), "web-user"))
            .build();

        return agent.streamEvents(new UserMessage(prompt), ctx)
            .map(event -> {
                try {
                    var node = mapper.createObjectNode();
                    String type = event.getClass().getSimpleName();
                    node.put("type", type);
                    node.put("timestamp", System.currentTimeMillis());
                    node.set("data", mapper.valueToTree(event));
                    return mapper.writeValueAsString(node);
                } catch (Exception e) {
                    return event("error", e.getMessage());
                }
            })
            .startWith(event("thinking", "Thinking..."))
            .concatWithValues(event("done", ""))
            .onErrorResume(e -> {
                log.error("Console chat stream error", e);
                return Flux.just(event("error", e.getMessage()), event("done", ""));
            })
            .doFinally(sig -> WorkspaceContext.clear());
    }

    @GetMapping("/agents")
    public Map<String, Object> listAgents() {
        return Map.of("agents", List.of(Map.of(
            "id", "default",
            "name", "majo",
            "description", "Majo AI Coding Agent",
            "workspace_dir", System.getProperty("user.dir"),
            "enabled", true,
            "startup_status", "running",
            "backend", "majo",
            "backend_capabilities", Map.of("workspace_ui", true, "code_files", true)
        )));
    }

    @GetMapping("/agent-stats")
    public Map<String, Object> agentStats() {
        return Map.of("agent_count", 1, "active_count", 1);
    }

    @GetMapping("/auth/status")
    public Map<String, Object> authStatus() {
        return Map.of("enabled", false);
    }

    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of("version", "0.1.0");
    }

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }

    @PostMapping("/console/chat/stop")
    public Map<String, String> stopChat() {
        return Map.of("status", "ok");
    }

    @GetMapping("/settings/language")
    public Map<String, String> language() {
        return Map.of("language", "en");
    }

    @GetMapping("/tools")
    public List<Map<String, String>> tools() {
        return List.of(
            Map.of("name", "read_file", "description", "Read file"),
            Map.of("name", "write_file", "description", "Write file"),
            Map.of("name", "edit_file", "description", "Edit file"),
            Map.of("name", "execute_command", "description", "Run shell command"),
            Map.of("name", "search_code", "description", "Search code"),
            Map.of("name", "list_directory", "description", "List directory"),
            Map.of("name", "find_symbol", "description", "Find symbol"),
            Map.of("name", "git_status", "description", "Git status"),
            Map.of("name", "git_diff", "description", "Git diff"),
            Map.of("name", "git_log", "description", "Git log"),
            Map.of("name", "git_commit", "description", "Git commit")
        );
    }

    @GetMapping("/token-usage")
    public TokenUsageSummary tokenUsage(
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider) {
        return new TokenUsageSummary(0, 0, 0, java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
    }

    @GetMapping("/token-usage/details")
    public List<TokenUsageRecord> tokenUsageDetails(
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider) {
        return List.of();
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
        return Map.of("events", InboxStore.listEvents(
                safeLimit, offset, source_type, status, agent_id, unread_only));
    }

    public record MarkInboxReadRequest(List<String> event_ids, boolean all) {}

    @PostMapping("/console/inbox/read")
    public Map<String, Object> markInboxRead(@RequestBody(required = false) MarkInboxReadRequest body) {
        boolean all = body != null && body.all;
        List<String> eventIds = body == null ? List.of()
                : (body.event_ids() == null ? List.of() : body.event_ids());
        int updated = all ? InboxStore.markAllRead() : InboxStore.markRead(eventIds);
        return Map.of("updated", updated);
    }

    @DeleteMapping("/console/inbox/events/{event_id}")
    public org.springframework.http.ResponseEntity<?> deleteInboxEvent(@PathVariable String event_id) {
        var result = InboxStore.deleteEvent(event_id);
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
                "run_id", result.runId()));
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
        return Map.of("messages", List.of(), "pending_approvals", List.of());
    }

    @GetMapping("/settings/upload-limit")
    public Map<String, Object> uploadLimit() {
        return Map.of("max_file_size_mb", 50, "allowed_types", List.of());
    }

    @PostMapping("/console/upload")
    public Map<String, String> consoleUpload() {
        return Map.of("url", "", "file_name", "");
    }

    @GetMapping("/providers/{provider_id}/oauth/start")
    public Map<String, String> oauthStart(@PathVariable String provider_id) {
        return Map.of("auth_url", "");
    }

    @GetMapping("/providers/{provider_id}/oauth/status")
    public Map<String, String> oauthStatus(@PathVariable String provider_id) {
        return Map.of("status", "pending");
    }

    @PostMapping("/harnesses/{provider_id}/login")
    public Map<String, String> harnessLogin(@PathVariable String provider_id) {
        return Map.of("status", "ok");
    }

    @PostMapping("/harnesses/{provider_id}/logout")
    public Map<String, String> harnessLogout(@PathVariable String provider_id) {
        return Map.of("status", "ok");
    }

    @PostMapping("/harnesses/{provider_id}/status")
    public Map<String, String> harnessStatus(@PathVariable String provider_id) {
        return Map.of("status", "ok");
    }

    @GetMapping("/config/heartbeat")
    public Map<String, String> globalHeartbeat() { return Map.of("status", "ok"); }

    @GetMapping("/config/channels")
    public List<Map<String, String>> globalChannels() { return List.of(); }

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

    private HarnessAgent resolveAgent(String workspace) {
        Path wsPath = workspace.isBlank() ? DEFAULT_WORKSPACE
            : Paths.get(workspace).toAbsolutePath().normalize();
        if (!workspace.isBlank() && !Files.isDirectory(wsPath)) {
            wsPath = DEFAULT_WORKSPACE;
        }
        return HarnessAgent.builder()
            .name("majo")
            .sysPrompt(SYS_PROMPT)
            .model(createModel())
            .toolkit(toolkit)
            .workspace(wsPath)
            .build();
    }

    private OpenAIChatModel createModel() {
        return OpenAIChatModel.builder()
            .apiKey(settingsService.getApiKey())
            .baseUrl(settingsService.getBaseUrl())
            .modelName(settingsService.getModelName())
            .build();
    }

    private String event(String type, String content) {
        try {
            var node = mapper.createObjectNode();
            node.put("type", type);
            node.put("content", content);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"type\":\"error\"}";
        }
    }
}
