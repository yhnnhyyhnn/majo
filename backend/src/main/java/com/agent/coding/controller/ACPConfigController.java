package com.agent.coding.controller;

import com.agent.coding.acp.ACPNodeRuntime;
import com.agent.coding.agent.AgentStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ACP (Agent Communication Protocol) configuration endpoints.
 *
 * Ported from qwenpaw config.py /config/acp. The ACP agents config is
 * per-agent (stored in the agent profile); the Node runtime path is global.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ACPConfigController {

    private static final Set<String> ALLOWED_ACP_TOOL_PARSE_MODES = Set.of(
        "call_title", "update_detail", "call_detail"
    );

    private String resolveAgentId(HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getParameter("agent");
        }
        return (agentId == null || agentId.isBlank()) ? "default" : agentId;
    }

    // ===== Global (agent resolved from X-Agent-Id) =====

    @GetMapping("/config/acp")
    public Map<String, Object> configAcp(HttpServletRequest request) {
        return acpConfigFor(resolveAgentId(request));
    }

    @PutMapping("/config/acp")
    public ResponseEntity<?> configAcpUpdate(@RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        return saveAcpConfig(resolveAgentId(request), body);
    }

    @GetMapping("/config/acp/{agent_name}")
    public ResponseEntity<?> configAcpAgent(@PathVariable String agent_name,
                                            HttpServletRequest request) {
        return acpAgentConfigFor(resolveAgentId(request), agent_name);
    }

    @PutMapping("/config/acp/{agent_name}")
    public ResponseEntity<?> configAcpAgentUpdate(@PathVariable String agent_name,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        return saveAcpAgentConfig(resolveAgentId(request), agent_name, body);
    }

    @GetMapping("/config/acp/node-runtime")
    public Map<String, Object> configAcpNode() {
        return ACPNodeRuntime.getNodeRuntimeStatus(AgentStore.getGlobalACPNodePath());
    }

    @PutMapping("/config/acp/node-runtime")
    public ResponseEntity<?> configAcpNodeUpdate(@RequestBody Map<String, Object> body) {
        String nodePath = Objects.toString(body.get("node_path"), "").trim();
        if (!nodePath.isEmpty()) {
            Map<String, Object> candidate = ACPNodeRuntime.resolveNodeRuntime(nodePath);
            if (!Boolean.TRUE.equals(candidate.get("available"))) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("reason_code", candidate.get("reason_code"));
                detail.put("reason", candidate.get("reason"));
                return ResponseEntity.badRequest().body(Map.of("detail", detail));
            }
        }
        AgentStore.setGlobalACPNodePath(nodePath);
        return ResponseEntity.ok(ACPNodeRuntime.getNodeRuntimeStatus(nodePath));
    }

    // ===== Agent-scoped =====

    @GetMapping("/agents/{agentId}/config/acp")
    public Map<String, Object> agentAcp(@PathVariable String agentId) {
        return acpConfigFor(agentId);
    }

    @PutMapping("/agents/{agentId}/config/acp")
    public ResponseEntity<?> agentAcpUpdate(@PathVariable String agentId,
                                            @RequestBody Map<String, Object> body) {
        return saveAcpConfig(agentId, body);
    }

    @GetMapping("/agents/{agentId}/config/acp/{agent_name}")
    public ResponseEntity<?> agentAcpAgent(@PathVariable String agentId,
                                           @PathVariable String agent_name) {
        return acpAgentConfigFor(agentId, agent_name);
    }

    @PutMapping("/agents/{agentId}/config/acp/{agent_name}")
    public ResponseEntity<?> agentAcpAgentUpdate(@PathVariable String agentId,
                                                 @PathVariable String agent_name,
                                                 @RequestBody Map<String, Object> body) {
        return saveAcpAgentConfig(agentId, agent_name, body);
    }

    @GetMapping("/agents/{agentId}/config/acp/node-runtime")
    public Map<String, Object> agentAcpNode(@PathVariable String agentId) {
        return configAcpNode();
    }

    @PutMapping("/agents/{agentId}/config/acp/node-runtime")
    public ResponseEntity<?> agentAcpNodeUpdate(@PathVariable String agentId,
                                                @RequestBody Map<String, Object> body) {
        return configAcpNodeUpdate(body);
    }

    // ===== Helpers =====

    private Map<String, Object> acpConfigFor(String agentId) {
        Map<String, Object> stored = AgentStore.getACPConfig(agentId);
        @SuppressWarnings("unchecked")
        Map<String, Object> agents = stored.get("agents") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        mergeDefaultAcpAgents(agents);
        Map<String, Object> result = new LinkedHashMap<>(stored);
        result.put("agents", agents);
        return result;
    }

    private ResponseEntity<?> saveAcpConfig(String agentId, Map<String, Object> body) {
        Map<String, Object> config = new LinkedHashMap<>(body);
        Object rawAgents = body.get("agents");
        if (rawAgents instanceof Map<?, ?> map) {
            Map<String, Object> agents = new LinkedHashMap<>((Map<String, Object>) map);
            mergeDefaultAcpAgents(agents);
            config.put("agents", agents);
        }
        AgentStore.saveACPConfig(agentId, config);
        return ResponseEntity.ok(acpConfigFor(agentId));
    }

    private ResponseEntity<?> acpAgentConfigFor(String agentId, String agentName) {
        Map<String, Object> acp = acpConfigFor(agentId);
        @SuppressWarnings("unchecked")
        Map<String, Object> agents = (Map<String, Object>) acp.get("agents");
        Object agent = agents.get(agentName);
        if (agent == null) {
            return ResponseEntity.status(404).body(Map.of("detail", "ACP agent '" + agentName + "' not found"));
        }
        return ResponseEntity.ok(agent);
    }

    private ResponseEntity<?> saveAcpAgentConfig(String agentId, String agentName,
                                                 Map<String, Object> body) {
        String mode = Objects.toString(body.get("tool_parse_mode"), "");
        if (!ALLOWED_ACP_TOOL_PARSE_MODES.contains(mode)) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "Invalid tool_parse_mode. Allowed values: call_detail, call_title, update_detail"
            ));
        }
        String name = agentName.trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "ACP agent name cannot be empty"));
        }
        Map<String, Object> stored = AgentStore.getACPConfig(agentId);
        @SuppressWarnings("unchecked")
        Map<String, Object> agents = stored.get("agents") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        agents.put(name, new LinkedHashMap<>(body));
        stored.put("agents", agents);
        AgentStore.saveACPConfig(agentId, stored);
        return ResponseEntity.ok(agents.get(name));
    }

    private void mergeDefaultAcpAgents(Map<String, Object> agents) {
        Map<String, Object> defaults = defaultAcpAgents();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            agents.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, Object> defaultAcpAgents() {
        Map<String, Object> agents = new LinkedHashMap<>();
        agents.put("opencode", acpAgent(true, "opencode", List.of("acp"), "update_detail"));
        agents.put("qwen_code", acpAgent(true, "qwen", List.of("--acp"), "call_detail"));
        agents.put("claude_code", acpAgent(true, "npx", List.of("-y", "@zed-industries/claude-agent-acp"), "update_detail"));
        agents.put("codex", acpAgent(true, "npx", List.of("-y", "@zed-industries/codex-acp"), "call_detail"));
        return agents;
    }

    private Map<String, Object> acpAgent(boolean enabled, String command, List<String> args, String toolParseMode) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("enabled", enabled);
        agent.put("command", command);
        agent.put("args", args);
        agent.put("env", Map.of());
        agent.put("trusted", true);
        agent.put("tool_parse_mode", toolParseMode);
        agent.put("stdio_buffer_limit_bytes", 50 * 1024 * 1024);
        return agent;
    }
}
