package com.agent.coding.controller;

import com.agent.coding.commands.CommandRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Control-command detection
 * the channel message path). The frontend calls this to decide whether a
 * message is a system control command (e.g. /stop, /approve) vs a normal
 * query before routing to chat or the approval flow.
 */
@RestController
@RequestMapping("/api/commands")
@CrossOrigin(origins = "*")
public class CommandsController {

    private final CommandRegistry registry;
    private final com.agent.coding.memory.MemoryCommandService memoryCommandService;

    public CommandsController(CommandRegistry registry,
                              com.agent.coding.memory.MemoryCommandService memoryCommandService) {
        this.registry = registry;
        this.memoryCommandService = memoryCommandService;
    }

    @PostMapping("/check")
    public Map<String, Object> check(@RequestBody Map<String, Object> body) {
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("is_control_command", registry.isControlCommand(text));
        result.put("command_token", null);
        return result;
    }

    /**
     * Execute a memory command (ADR-0009). Accepts the raw command text
     * (starting with /memory) plus the owning agent id; returns the
     * user-facing markdown reply.
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody Map<String, Object> body) {
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text")).strip();
        String agentId = body.get("agent_id") == null || String.valueOf(body.get("agent_id")).isBlank()
                ? "default" : String.valueOf(body.get("agent_id")).strip();
        String reply;
        if (!text.toLowerCase().startsWith("/memory")) {
            reply = "仅支持 /memory 命令。";
        } else {
            String args = text.length() > 7 ? text.substring(7) : "";
            reply = memoryCommandService.execute(agentId, args);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        return result;
    }
}
