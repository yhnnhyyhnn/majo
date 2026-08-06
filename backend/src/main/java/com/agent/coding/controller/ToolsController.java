package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.dto.ToolInfo;
import com.agent.coding.skill.SkillService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in tools management (per-agent enabled / async_execution / config),
 * ported from qwenpaw app/routers/tools.py. Tool state is persisted in the
 * agent profile under the {@code tools} key.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ToolsController {

    private static final List<Map<String, String>> BUILTIN_TOOLS = List.of(
        tool("read_file", "Read file contents", "\uD83D\uDCC4"),
        tool("write_file", "Write content to file", "\u270D\uFE0F"),
        tool("edit_file", "Edit file using find-and-replace", "\uD83D\uDD8A\uFE0F"),
        tool("append_file", "Append content to a file", "\uD83D\uDCCE"),
        tool("grep_search", "Search file contents by pattern", "\uD83D\uDD0D"),
        tool("glob_search", "Find files matching a glob pattern", "\uD83D\uDCC1"),
        tool("execute_shell_command", "Execute shell commands", "\uD83D\uDCBB"),
        tool("send_file_to_user", "Send files to user", "\uD83D\uDCE4"),
        tool("browser_use", "Browser automation and web interaction", "\uD83C\uDF10"),
        tool("web_search", "Search the web for real-time information", "\uD83D\uDD0E"),
        tool("web_fetch", "Fetch and read content from a URL", "\uD83D\uDCE5"),
        tool("desktop_screenshot", "Capture desktop screenshots", "\uD83D\uDCF8"),
        tool("view_image", "Load an image into LLM context for visual analysis", "\uD83D\uDDBC\uFE0F"),
        tool("view_video", "Load a video into LLM context for visual analysis", "\uD83C\uDFA5"),
        tool("get_current_time", "Get current date and time", "\uD83D\uDD50"),
        tool("set_user_timezone", "Set user timezone", "\uD83C\uDF0D"),
        tool("get_token_usage", "Get llm token usage", "\uD83D\uDCCA"),
        tool("list_agents", "List configured agents from the local API", "\uD83E\uDD16"),
        tool("chat_with_agent", "Send a message to another configured agent and wait for the response", "\uD83D\uDCAC"),
        tool("submit_to_agent", "Submit a background task to another configured agent", "\uD83D\uDCE8"),
        tool("check_agent_task", "Check the status of a background agent task", "\u23F3"),
        tool("spawn_subagent", "Spawn an ephemeral sub-task within the current workspace", "\uD83D\uDD00"),
        tool("delegate_external_agent", "Delegate work to an external ACP agent runner", "\uD83D\uDCE1"),
        tool("materialize_skill", "Materialize a skill definition into the workspace", "\uD83E\uDDE9"),
        tool("ast_search", "Search code by AST pattern (coding mode)", "\uD83C\uDF33")
    );

    private static Map<String, String> tool(String name, String desc, String icon) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", desc);
        t.put("icon", icon);
        return t;
    }

    private String resolveAgentId(HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getParameter("agent");
        }
        return (agentId == null || agentId.isBlank()) ? "default" : agentId;
    }

    private Map<String, Object> builtinToolsFor(String agentId) {
        Map<String, Object> stored = AgentStore.getToolsConfig(agentId);
        @SuppressWarnings("unchecked")
        Map<String, Object> builtin = stored.get("builtin_tools") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        for (Map<String, String> def : BUILTIN_TOOLS) {
            String name = def.get("name");
            if (!builtin.containsKey(name)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("enabled", true);
                entry.put("description", def.get("description"));
                entry.put("display_to_user", true);
                entry.put("async_execution", false);
                entry.put("icon", def.get("icon"));
                entry.put("config", Map.of());
                builtin.put(name, entry);
            }
        }
        return builtin;
    }

    private Map<String, Object> findTool(String agentId, String toolName) {
        Map<String, Object> builtin = builtinToolsFor(agentId);
        Object entry = builtin.get(toolName);
        if (entry == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> tool = entry instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        if (!tool.containsKey("name")) {
            tool.put("name", toolName);
        }
        return tool;
    }

    private void persist(String agentId, Map<String, Object> builtin) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("builtin_tools", builtin);
        AgentStore.saveToolsConfig(agentId, config);
    }

    private ToolInfo toToolInfo(Map<String, Object> tool) {
        ToolInfo info = new ToolInfo(
            SkillService.str(tool.get("name")),
            SkillService.bool(tool.get("enabled"), true),
            SkillService.str(tool.get("description")),
            SkillService.str(tool.get("icon"))
        );
        info.setAsyncExecution(SkillService.bool(tool.get("async_execution"), false));
        Object config = tool.get("config");
        info.setConfigValues(config instanceof Map<?, ?> map ? map : Map.of());
        return info;
    }

    private ResponseEntity<?> notFound(String toolName) {
        return ResponseEntity.status(404).body(Map.of("detail", "Tool '" + toolName + "' not found"));
    }

    // ===== Global (agent resolved from X-Agent-Id) =====

    @GetMapping("/tools")
    public List<ToolInfo> listTools(HttpServletRequest request) {
        Map<String, Object> builtin = builtinToolsFor(resolveAgentId(request));
        List<ToolInfo> result = new ArrayList<>();
        for (Object value : builtin.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = value instanceof Map<?, ?> map
                ? (Map<String, Object>) map : new LinkedHashMap<>();
            result.add(toToolInfo(tool));
        }
        return result;
    }

    @PatchMapping("/tools/{tool_name}/toggle")
    public ResponseEntity<?> toggleTool(@PathVariable String tool_name, HttpServletRequest request) {
        String agentId = resolveAgentId(request);
        Map<String, Object> tool = findTool(agentId, tool_name);
        if (tool == null) {
            return notFound(tool_name);
        }
        tool.put("enabled", !SkillService.bool(tool.get("enabled"), true));
        Map<String, Object> builtin = builtinToolsFor(agentId);
        builtin.put(tool_name, tool);
        persist(agentId, builtin);
        return ResponseEntity.ok(toToolInfo(tool));
    }

    @PatchMapping("/tools/{tool_name}/async-execution")
    public ResponseEntity<?> updateAsyncExecution(@PathVariable String tool_name,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        String agentId = resolveAgentId(request);
        Map<String, Object> tool = findTool(agentId, tool_name);
        if (tool == null) {
            return notFound(tool_name);
        }
        tool.put("async_execution", SkillService.bool(body.get("async_execution"), false));
        Map<String, Object> builtin = builtinToolsFor(agentId);
        builtin.put(tool_name, tool);
        persist(agentId, builtin);
        return ResponseEntity.ok(toToolInfo(tool));
    }

    @GetMapping("/tools/{tool_name}/config")
    public ResponseEntity<?> getToolConfig(@PathVariable String tool_name, HttpServletRequest request) {
        Map<String, Object> tool = findTool(resolveAgentId(request), tool_name);
        if (tool == null) {
            return notFound(tool_name);
        }
        Object config = tool.get("config");
        return ResponseEntity.ok(config instanceof Map<?, ?> map ? map : Map.of());
    }

    @PostMapping("/tools/{tool_name}/config")
    public ResponseEntity<?> updateToolConfig(@PathVariable String tool_name,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        String agentId = resolveAgentId(request);
        Map<String, Object> tool = findTool(agentId, tool_name);
        if (tool == null) {
            return notFound(tool_name);
        }
        Object rawConfig = body.get("config");
        Map<String, Object> config = rawConfig instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        tool.put("config", config);
        Map<String, Object> builtin = builtinToolsFor(agentId);
        builtin.put(tool_name, tool);
        persist(agentId, builtin);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("message", "Configuration updated");
        return ResponseEntity.ok(result);
    }

    // ===== Agent-scoped (port of qwenpaw agent_scoped /agents/{agentId}/tools) =====

    @GetMapping("/agents/{agentId}/tools")
    public Object agentListTools(@PathVariable String agentId) {
        List<ToolInfo> result = new ArrayList<>();
        for (Object value : builtinToolsFor(agentId).values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tool = value instanceof Map<?, ?> map
                ? (Map<String, Object>) map : new LinkedHashMap<>();
            result.add(toToolInfo(tool));
        }
        return result;
    }

    @PatchMapping("/agents/{agentId}/tools/{tool_name}/toggle")
    public Object agentToggleTool(@PathVariable String agentId, @PathVariable String tool_name) {
        Map<String, Object> tool = findTool(agentId, tool_name);
        if (tool == null) {
            return notFound(tool_name);
        }
        tool.put("enabled", !SkillService.bool(tool.get("enabled"), true));
        Map<String, Object> builtin = builtinToolsFor(agentId);
        builtin.put(tool_name, tool);
        persist(agentId, builtin);
        return toToolInfo(tool);
    }

    @PatchMapping("/agents/{agentId}/tools/{tool_name}/async-execution")
    public Object agentUpdateAsyncExecution(@PathVariable String agentId, @PathVariable String tool_name,
                                            @RequestBody Map<String, Object> body) {
        Map<String, Object> tool = findTool(agentId, tool_name);
        if (tool == null) {
            return notFound(tool_name);
        }
        tool.put("async_execution", SkillService.bool(body.get("async_execution"), false));
        Map<String, Object> builtin = builtinToolsFor(agentId);
        builtin.put(tool_name, tool);
        persist(agentId, builtin);
        return toToolInfo(tool);
    }

    @GetMapping("/agents/{agentId}/tools/{tool_name}/config")
    public Object agentGetToolConfig(@PathVariable String agentId, @PathVariable String tool_name) {
        Map<String, Object> tool = findTool(agentId, tool_name);
        if (tool == null) {
            return notFound(tool_name);
        }
        Object config = tool.get("config");
        return config instanceof Map<?, ?> map ? map : Map.of();
    }

    @PostMapping("/agents/{agentId}/tools/{tool_name}/config")
    public Object agentUpdateToolConfig(@PathVariable String agentId, @PathVariable String tool_name,
                                        @RequestBody Map<String, Object> body) {
        Map<String, Object> tool = findTool(agentId, tool_name);
        if (tool == null) {
            return notFound(tool_name);
        }
        Object rawConfig = body.get("config");
        Map<String, Object> config = rawConfig instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        tool.put("config", config);
        Map<String, Object> builtin = builtinToolsFor(agentId);
        builtin.put(tool_name, tool);
        persist(agentId, builtin);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("message", "Configuration updated");
        return result;
    }
}
