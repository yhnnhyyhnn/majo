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
 * Speaks the minimal ACP JSON-RPC subset: initialize → agent/task → result.
 *
 * <p>Agent entries are configured under the agent profile's {@code acp}
 * section (/api/config/acp), each with {@code command}, {@code args}, {@code env}.
 */
@Component
public class DelegateExternalAgentTool {

    private static final Logger log = LoggerFactory.getLogger(DelegateExternalAgentTool.class);
    private static final long TIMEOUT_SECONDS = 300;
    private static final int MAX_RESULT_CHARS = 16_000;

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
                 BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
                sendRaw(out, 1, "initialize", Map.of("protocolVersion", 1, "clientCapabilities", Map.of()));
                readResponse(in); // initialize reply
                sendRaw(out, 2, "agent/task", Map.of(
                        "id", taskId,
                        "prompt", task,
                        "options", Map.of("maxSteps", 20)));
                result = collectTaskResult(in, process);
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

    /** Read one framed JSON message; returns null on EOF. */
    private static com.fasterxml.jackson.databind.JsonNode readResponse(BufferedReader in)
            throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            if (!line.startsWith("Content-Length:")) {
                continue;
            }
            int len = Integer.parseInt(line.substring("Content-Length:".length()).trim());
            if (len <= 0 || len > 50 * 1024 * 1024) {
                continue;
            }
            char[] buf = new char[len];
            int read = 0;
            while (read < len) {
                int r = in.read(buf, read, len - read);
                if (r < 0) {
                    return null;
                }
                read += r;
            }
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(new String(buf));
        }
        return null;
    }

    /** After agent/task, keep reading framed messages until the final result. */
    private static String collectTaskResult(BufferedReader in, Process process) throws Exception {
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
            if ("agent/task/progress".equals(method)) {
                continue; // progress notifications — skip
            }
            if (node.path("id").asInt(-1) == 2) {
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
        if (!(entry instanceof Map<?, ?> em)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) em);
        if (!SkillService.bool(result.get("enabled"), true)) {
            return null;
        }
        return result;
    }
}
