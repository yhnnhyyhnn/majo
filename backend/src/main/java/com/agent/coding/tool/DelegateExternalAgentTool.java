package com.agent.coding.tool;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Delegate a task to an external ACP (Agent Communication Protocol) agent
 * runner (opencode / qwen --acp / claude-code-acp / codex-acp) over stdio.
 *
 * <p><b>Session-first protocol</b> (phase 1 of the ACP roadmap): after
 * {@code initialize} the tool opens a session ({@code session/new}), submits
 * the task ({@code session/prompt}) and consumes the {@code session/update}
 * notification stream — accumulating agent message chunks and a compact
 * tool-call trace. Runners that do not implement the session API fall back
 * to the legacy one-shot {@code agent/task} RPC. Runner-initiated requests
 * ({@code session/request_permission} etc.) are answered on the pipe —
 * permission requests are denied (no human watches this pipe), anything else
 * is rejected with a JSON-RPC error — so a runner can never hang us.
 */
@Component
public class DelegateExternalAgentTool {

    private static final Logger log = LoggerFactory.getLogger(DelegateExternalAgentTool.class);
    private static final long TIMEOUT_SECONDS = 300;
    private static final int MAX_RESULT_CHARS = 16_000;

    private final com.agent.coding.acp.AcpPermissionBridge permissionBridge;

    public DelegateExternalAgentTool(com.agent.coding.acp.AcpPermissionBridge permissionBridge) {
        this.permissionBridge = permissionBridge;
    }
    private static final int MAX_TRACE_LINES = 12;

    @Tool(name = "delegate_external_agent",
            description = "把任务委托给外部 ACP Agent(如 opencode/qwen --acp/claude-code-acp)")
    public String delegateExternalAgent(
        @ToolParam(name = "task", description = "要委托的任务描述") String task,
        @ToolParam(name = "agent_name", description = "ACP Agent 名称(默认 opencode)") String agentName
    ) {
        if (task == null || task.isBlank()) {
            return "错误: task 不能为空";
        }
        String name = agentName == null || agentName.isBlank() ? "opencode" : agentName.trim();
        try {
            Map<String, Object> entry = acpAgentEntry(name);
            if (entry == null) {
                return "错误: ACP Agent '" + name + "' 未配置或在 /api/config/acp 中已禁用";
            }
            String command = SkillService.str(entry.get("command"));
            List<String> args = SkillService.toStringList(entry.get("args"));
            @SuppressWarnings("unchecked")
            Map<String, String> env = entry.get("env") instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, String>) m) : Map.of();

            ProcessBuilder pb = new ProcessBuilder(join(command, args));
            pb.redirectErrorStream(true);
            Map<String, String> processEnv = pb.environment();
            processEnv.putAll(env);
            try {
                pb.directory(com.agent.coding.WorkspaceContext.get().toFile());
            } catch (Exception ignored) {
            }
            Process process = pb.start();

            String result;
            try (Writer out = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                 java.io.InputStream in = process.getInputStream()) {
                sendRaw(out, 1, "initialize", Map.of("protocolVersion", 1, "clientCapabilities", Map.of()));
                readResponse(in); // initialize reply

                result = runSessionFlow(out, in, process, task);
                if (result == null) {
                    // Runner rejected session/new (legacy agent/task only).
                    log.info("[delegate:{}] no session API, falling back to agent/task", name);
                    result = runLegacyFlow(out, in, process, task);
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            if (result == null || result.isBlank()) {
                return "外部 Agent '" + name + "' 未返回结果(可能不支持 ACP 协议或执行超时)";
            }
            return result.length() > MAX_RESULT_CHARS
                    ? result.substring(0, MAX_RESULT_CHARS) + "\n...[结果过长已截断]" : result;
        } catch (Exception e) {
            log.warn("[delegate] {} failed: {}", name, e.getMessage());
            return "委托失败: " + e.getMessage();
        }
    }

    // ── Session flow (ACP standard: session/new → session/prompt) ────

    /** Returns the agent's answer, or null when the runner has no session API. */
    private String runSessionFlow(Writer out, java.io.InputStream in, Process process,
                                  String task) throws Exception {
        sendRaw(out, 2, "session/new", Map.of(
                "cwd", com.agent.coding.WorkspaceContext.get().toString(),
                "mcpServers", List.of()));
        FrameCollector collector = new FrameCollector();
        com.fasterxml.jackson.databind.JsonNode newSess =
                pumpUntilResponse(out, in, process, 2, permissionBridge, collector, System.nanoTime());
        if (newSess == null) {
            return timeoutNote();
        }
        if (newSess.has("error")) {
            log.info("[delegate] session/new rejected: {}",
                    newSess.path("error").path("message").asText(""));
            return null; // legacy fallback
        }
        String sessionId = newSess.path("result").path("sessionId").asText("");
        if (sessionId.isBlank()) {
            return null; // no sessionId — treat as no session support
        }

        sendRaw(out, 3, "session/prompt", Map.of(
                "sessionId", sessionId,
                "prompt", List.of(Map.of("type", "text", "text", task))));
        collector.expectStopReason = true;
        com.fasterxml.jackson.databind.JsonNode promptResp =
                pumpUntilResponse(out, in, process, 3, permissionBridge, collector, System.nanoTime());
        if (promptResp == null) {
            return timeoutNote();
        }
        if (promptResp.has("error")) {
            return "外部 Agent 返回错误: "
                    + promptResp.path("error").path("message").asText("unknown");
        }

        String text = collector.agentText.toString().trim();
        if (text.isEmpty()) {
            text = extractText(promptResp.path("result"));
        }
        String stop = promptResp.path("result").path("stopReason").asText("end_turn");
        StringBuilder sb = new StringBuilder(text);
        if (!"end_turn".equals(stop)) {
            sb.append("\n\n[停止原因: ").append(stop).append("]");
        }
        String trace = collector.traceSummary();
        if (!trace.isEmpty()) {
            sb.append("\n\n").append(trace);
        }
        return sb.toString().trim();
    }

    // ── Legacy flow (agent/task one-shot RPC) ─────────────────────────

    private String runLegacyFlow(Writer out, java.io.InputStream in, Process process,
                                 String task) throws Exception {
        sendRaw(out, 4, "agent/task", Map.of(
                "id", "task-" + UUID.randomUUID().toString().substring(0, 8),
                "prompt", task,
                "options", Map.of("maxSteps", 20)));
        String result = collectTaskResult(out, in, process);
        return result == null ? "" : result;
    }

    private static String timeoutNote() {
        return "[超时] 外部 Agent " + TIMEOUT_SECONDS + "s 内未完成";
    }

    // ── Frame collector + central pump ────────────────────────────────

    /** Accumulates the visible outcome of one delegated turn. */
    private static final class FrameCollector {
        final StringBuilder agentText = new StringBuilder();
        /** toolCallId → "title — status" (insertion-ordered trace). */
        final LinkedHashMap<String, String> toolTrace = new LinkedHashMap<>();
        boolean expectStopReason;

        void onSessionUpdate(com.fasterxml.jackson.databind.JsonNode update) {
            String variant = update.path("sessionUpdate").asText("");
            switch (variant) {
                case "agent_message_chunk" -> {
                    String t = chunkText(update.path("content"));
                    if (!t.isEmpty()) {
                        agentText.append(t);
                    }
                }
                case "tool_call", "tool_call_update" -> {
                    String id = update.path("toolCallId").asText("");
                    if (id.isBlank()) {
                        return;
                    }
                    String title = update.path("title").asText("");
                    if (title.isBlank()) {
                        title = rawInputSummary(update.path("rawInput"));
                    }
                    String status = update.path("status").asText("");
                    String prev = toolTrace.get(id);
                    String combined = title.isBlank() ? String.valueOf(prev) : title;
                    if (!status.isBlank()) {
                        combined = combined + " [" + status + "]";
                    }
                    toolTrace.put(id, combined);
                }
                default -> {
                    // plan / available_commands_update / etc. — not surfaced in phase 1
                }
            }
        }

        /** Compact footer summarizing tool calls (capped). */
        String traceSummary() {
            if (toolTrace.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("[外部 Agent 工具调用]\n");
            int n = 0;
            for (String line : toolTrace.values()) {
                if (n++ >= MAX_TRACE_LINES) {
                    sb.append("…(其余 ").append(toolTrace.size() - MAX_TRACE_LINES).append(" 条省略)\n");
                    break;
                }
                sb.append("- ").append(line).append("\n");
            }
            return sb.toString().trim();
        }
    }

    private static String chunkText(com.fasterxml.jackson.databind.JsonNode content) {
        if (content == null || content.isMissingNode()) {
            return "";
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (com.fasterxml.jackson.databind.JsonNode b : content) {
                sb.append(chunkText(b));
            }
            return sb.toString();
        }
        if ("text".equals(content.path("type").asText("text"))) {
            return content.path("text").asText("");
        }
        return "";
    }

    private static String rawInputSummary(com.fasterxml.jackson.databind.JsonNode rawInput) {
        if (rawInput == null || !rawInput.isObject() || rawInput.isEmpty()) {
            return "";
        }
        for (String key : new String[]{"command", "path", "file_path", "url", "query"}) {
            String v = rawInput.path(key).asText("");
            if (!v.isBlank()) {
                return v.length() > 80 ? v.substring(0, 80) + "…" : v;
            }
        }
        return rawInput.toString().length() > 80
                ? rawInput.toString().substring(0, 80) + "…" : rawInput.toString();
    }

    /**
     * Read framed frames until the JSON-RPC response with {@code respId}
     * arrives (or the deadline passes). Along the way: collect session
     * updates into {@code collector}; answer runner requests — permission
     * requests get a Denied outcome, anything else a method-not-supported
     * error — so the runner can never hang waiting for us.
     *
     * @return the response node, or null on timeout/EOF
     */
    private com.fasterxml.jackson.databind.JsonNode pumpUntilResponse(
            Writer out, java.io.InputStream in, Process process, int respId,
            com.agent.coding.acp.AcpPermissionBridge bridge, FrameCollector collector,
            long deadlineNanos) throws Exception {
        while (System.nanoTime() < deadlineNanos) {
            if (process != null && !process.isAlive()) {
                break;
            }
            com.fasterxml.jackson.databind.JsonNode node = readResponse(in);
            if (node == null) {
                break;
            }
            String method = node.path("method").asText("");
            boolean isRequest = node.has("id") && !node.get("id").isNull();
            if (!method.isEmpty()) {
                if ("session/update".equals(method)) {
                    collector.onSessionUpdate(node.path("params").path("update"));
                } else if (isRequest) {
                    Map<String, Object> payload;
                    if (method.endsWith("request_permission")) {
                        // Phase 2: interactive context → approval card on the
                        // majo session; otherwise instant deny. Null bridge
                        // (tests) degrades to the same instant deny.
                        payload = bridge != null
                                ? bridge.resolve(node.path("params"))
                                : Map.of("outcome", Map.of("outcome", "cancelled"));
                    } else {
                        payload = Map.of("error", Map.of("code", -32601, "message",
                                "method not supported by delegating client: " + method));
                    }
                    replyRaw(out, node.get("id").asInt(), payload);
                }
                continue; // requests/notifications are never the task result
            }
            if (node.path("id").asInt(-1) == respId && node.has("result")) {
                return node;
            }
            // stale/unknown response — ignore
        }
        if (process != null) {
            process.destroyForcibly();
        }
        return null;
    }

    // ── Legacy one-shot collector (agent/task runners) ────────────────

    /**
     * After agent/task, keep reading framed messages until the final result.
     * Runner-initiated requests are answered (permission → denied, others →
     * error) so they can never hang us or be mistaken for the result.
     */
    private static String collectTaskResult(Writer out, java.io.InputStream in, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        StringBuilder combined = new StringBuilder();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                break;
            }
            com.fasterxml.jackson.databind.JsonNode node = readResponse(in);
            if (node == null) {
                break;
            }
            String method = node.path("method").asText("");
            if (!method.isEmpty()) {
                if (node.has("id") && !node.get("id").isNull()) {
                    if (method.endsWith("request_permission")) {
                        replyRaw(out, node.get("id").asInt(), Map.of(
                                "outcome", Map.of("outcome", "cancelled")));
                    } else {
                        replyRaw(out, node.get("id").asInt(), Map.of(
                                "error", Map.of("code", -32601, "message",
                                        "method not supported by delegating client: " + method)));
                    }
                }
                continue;
            }
            if (node.path("id").asInt(-1) == 2 && node.has("result")) {
                String status = node.path("result").path("status").asText("");
                combined.append(extractText(node.path("result")));
                if ("completed".equals(status) || "cancelled".equals(status) || "failed".equals(status)) {
                    return combined.toString().trim();
                }
            }
            if (combined.length() > MAX_RESULT_CHARS) {
                break;
            }
        }
        process.destroyForcibly();
        return combined.toString().trim();
    }

    private static String extractText(com.fasterxml.jackson.databind.JsonNode result) {
        StringBuilder sb = new StringBuilder();
        com.fasterxml.jackson.databind.JsonNode msg = result.path("message");
        if (msg.isTextual()) {
            sb.append(msg.asText());
        }
        com.fasterxml.jackson.databind.JsonNode blocks = result.path("result");
        if (blocks.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode b : blocks) {
                String text = b.path("text").asText("");
                if (!text.isEmpty()) {
                    sb.append(text).append("\n");
                }
            }
        }
        if (sb.isEmpty()) {
            String raw = result.toString();
            sb.append(raw.length() > 2000 ? raw.substring(0, 2000) : raw);
        }
        return sb.toString().trim();
    }

    // ── config lookup ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> acpAgentEntry(String agentName) {
        Map<String, Object> acp = AgentStore.getACPConfig("default");
        Object agents = acp.get("agents");
        if (!(agents instanceof Map<?, ?> m)) {
            return null;
        }
        Object entry = m.get(agentName);
        if (!(entry instanceof Map<?, ?> e)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : e.entrySet()) {
            out.put(String.valueOf(en.getKey()), en.getValue());
        }
        Object enabled = out.get("enabled");
        if (enabled != null && !Boolean.parseBoolean(String.valueOf(enabled))) {
            return null;
        }
        return out;
    }

    // ── ACP stdio protocol ────────────────────────────────────────────

    private static List<String> join(String command, List<String> args) {
        List<String> all = new ArrayList<>();
        all.add(command);
        all.addAll(args);
        return all;
    }

    /** Write one Content-Length framed JSON-RPC message (no response read). */
    private static void sendRaw(Writer out, int id, String method, Map<String, Object> params)
            throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.put("params", params);
        byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(msg);
        out.write("Content-Length: " + bytes.length + "\r\n\r\n");
        out.write(new String(bytes, StandardCharsets.UTF_8));
        out.flush();
    }

    /** Answer a runner request: bare JSON-RPC result/error (no method). */
    private static void replyRaw(Writer out, int id, Map<String, Object> payload)
            throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.putAll(payload);
        byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(msg);
        out.write("Content-Length: " + bytes.length + "\r\n\r\n");
        out.write(new String(bytes, StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Read one Content-Length framed JSON message at the BYTE level
     * (Content-Length counts UTF-8 bytes; a char-based read desyncs on
     * multibyte content). Returns null on EOF or truncated frame.
     */
    private static com.fasterxml.jackson.databind.JsonNode readResponse(java.io.InputStream in)
            throws Exception {
        int length = -1;
        StringBuilder headerLine = new StringBuilder();
        int c;
        try {
            while ((c = in.read()) != -1) {
                if (c == '\r') {
                    continue; // header lines are CRLF-terminated; CR never part of the value
                }
                if (c == '\n') {
                    if (headerLine.length() == 0) {
                        break; // blank line — headers done, body follows
                    }
                    String h = headerLine.toString();
                    if (h.startsWith("Content-Length:")) {
                        length = Integer.parseInt(h.substring("Content-Length:".length()).trim());
                    }
                    headerLine.setLength(0);
                    continue;
                }
                headerLine.append((char) c);
                if (headerLine.length() > 16 * 1024) {
                    return null; // runaway header
                }
            }
        } catch (java.io.IOException e) {
            return null; // pipe aborted (process killed) — treat as EOF
        }
        if (length < 0) {
            return null;
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
            return null; // truncated frame
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
    }
}
