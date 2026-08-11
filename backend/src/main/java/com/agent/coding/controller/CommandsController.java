package com.agent.coding.controller;

import com.agent.coding.commands.CommandRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Control-command detection, ported from qwenpaw (CommandRegistry usage in
 * the channel message path). The frontend calls this to decide whether a
 * message is a system control command (e.g. /stop, /approve) vs a normal
 * query before routing to chat or the approval flow.
 */
@RestController
@RequestMapping("/api/commands")
@CrossOrigin(origins = "*")
public class CommandsController {

    private final CommandRegistry registry;

    public CommandsController(CommandRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/check")
    public Map<String, Object> check(@RequestBody Map<String, Object> body) {
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("is_control_command", registry.isControlCommand(text));
        result.put("command_token", null);
        return result;
    }
}
