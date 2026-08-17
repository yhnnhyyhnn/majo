package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loop mode discovery + custom mode persistence
 * app/routers/loops.py. Built-in modes are static; custom modes persist in the
 * agent's {@code running.loop.custom_modes} config.
 */
@RestController
@RequestMapping("/api/loops")
@CrossOrigin(origins = "*")
public class LoopsController {

    private String resolveAgentId(HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getParameter("agent");
        }
        return (agentId == null || agentId.isBlank()) ? "default" : agentId;
    }

    private static Map<String, Object> builtinLoop(String id, String slashCommand, String description) {
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("id", id);
        mode.put("name", id);
        mode.put("slash_command", slashCommand);
        mode.put("description", description);
        mode.put("source", "builtin");
        return mode;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> customModes(String agentId) {
        Map<String, Object> running = AgentStore.getRunningConfig(agentId);
        Map<String, Object> loop = SkillService.asMap(running.get("loop"));
        Object cm = loop.get("custom_modes");
        if (cm instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    result.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    private static void persistCustomModes(String agentId, List<Map<String, Object>> modes) {
        Map<String, Object> running = AgentStore.getRunningConfig(agentId);
        Map<String, Object> loop = SkillService.asMap(running.get("loop"));
        loop.put("custom_modes", modes);
        running.put("loop", loop);
        AgentStore.saveRunningConfig(agentId, running, AgentStore.getApprovalLevel(agentId));
    }

    private static Map<String, Object> customToInfo(Map<String, Object> custom) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", SkillService.str(custom.get("id")));
        info.put("name", SkillService.str(custom.get("name")));
        info.put("slash_command", SkillService.str(custom.get("slash_command")));
        info.put("description", SkillService.str(custom.get("description"), ""));
        info.put("source", "custom");
        return info;
    }

    @GetMapping
    public List<Map<String, Object>> listLoops(HttpServletRequest request) {
        String agentId = resolveAgentId(request);
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(builtinLoop("default", "", "The standard guarded agent loop."));
        result.add(builtinLoop("goal", "goal", "Set a goal and work until it is done."));
        result.add(builtinLoop("mission", "mission", "Run a persistent multi-step mission."));
        for (Map<String, Object> custom : customModes(agentId)) {
            result.add(customToInfo(custom));
        }
        return result;
    }

    @GetMapping("/gates/catalog")
    public List<Map<String, Object>> listGateCatalog() {
        List<Map<String, Object>> catalog = new ArrayList<>();
        catalog.add(gateEntry("iteration", "Iteration limit",
                "Stop after a fixed number of loop iterations.", "limits",
                Map.of("max_iterations", Map.of("type", "integer", "default", 40, "minimum", 1, "maximum", 500))));
        catalog.add(gateEntry("doom_loop", "Repetition protection",
                "Detect repeated tool calls and change strategy.", "safety",
                Map.of("window_size", Map.of("type", "integer", "default", 3, "minimum", 2, "maximum", 20),
                        "similarity_threshold", Map.of("type", "number", "default", 1.0, "minimum", 0.0, "maximum", 1.0))));
        catalog.add(gateEntry("token_budget", "Token budget",
                "Limit prompt and completion token usage.", "limits",
                Map.of("max_total_tokens", Map.of("type", "integer", "default", 120000, "minimum", 1))));
        catalog.add(gateEntry("timeout", "Loop time limit",
                "Stop at the next loop boundary after elapsed time.", "limits",
                Map.of("max_seconds", Map.of("type", "number", "default", 1800.0, "minimum", 1.0, "maximum", 86400.0))));
        catalog.add(gateEntry("tool_call_budget", "Tool-call budget",
                "Limit all calls and selected tools.", "limits",
                Map.of("max_calls", Map.of("type", "integer", "default", 30, "minimum", 1, "maximum", 10000))));
        catalog.add(gateEntry("qualitative_rubric", "Qualitative completion check",
                "Check text responses without tool calls using natural-language criteria.", "quality",
                Map.of("rubric", Map.of("type", "string", "default",
                        "Verify the task before stopping. Continue if work remains.")),
                "completion_rubric"));
        catalog.add(gateEntry("completion_rubric", "Completion signal check",
                "Check text responses without tool calls for a completion signal.", "quality",
                Map.of("completion_signal", Map.of("type", "string", "default", "COMPLETED")),
                "completion_rubric", "model_call"));
        return catalog;
    }

    private static Map<String, Object> gateEntry(String type, String title, String description,
                                                 String category, Map<String, Object> params,
                                                 String exclusiveGroup, String cost) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("title", title);
        entry.put("description", description);
        entry.put("category", category);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", params);
        entry.put("schema", schema);
        entry.put("cost", cost);
        entry.put("exclusive_group", exclusiveGroup);
        return entry;
    }

    private static Map<String, Object> gateEntry(String type, String title, String description,
                                                 String category, Map<String, Object> params,
                                                 String exclusiveGroup) {
        return gateEntry(type, title, description, category, params, exclusiveGroup, "none");
    }

    private static Map<String, Object> gateEntry(String type, String title, String description,
                                                 String category, Map<String, Object> params) {
        return gateEntry(type, title, description, category, params, null, "none");
    }

    @GetMapping("/custom")
    public List<Map<String, Object>> listCustomModes(HttpServletRequest request) {
        return customModes(resolveAgentId(request));
    }

    @PostMapping("/custom")
    public ResponseEntity<Map<String, Object>> createCustomMode(@RequestBody Map<String, Object> mode,
                                                                HttpServletRequest request) {
        String agentId = resolveAgentId(request);
        String id = SkillService.str(mode.get("id"));
        if (id.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "id is required"));
        }
        List<Map<String, Object>> modes = customModes(agentId);
        for (Map<String, Object> existing : modes) {
            if (id.equals(SkillService.str(existing.get("id")))) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "Mode ID already exists"));
            }
        }
        modes.add(mode);
        persistCustomModes(agentId, modes);
        return ResponseEntity.status(HttpStatus.CREATED).body(mode);
    }

    @PutMapping("/custom/{mode_id}")
    public ResponseEntity<?> updateCustomMode(@PathVariable String mode_id,
                                              @RequestBody Map<String, Object> mode,
                                              HttpServletRequest request) {
        String agentId = resolveAgentId(request);
        if (!mode_id.equals(SkillService.str(mode.get("id")))) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail", "Mode ID cannot change"));
        }
        List<Map<String, Object>> modes = customModes(agentId);
        int index = findMode(modes, mode_id);
        if (index < 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "Mode not found"));
        }
        modes.set(index, mode);
        persistCustomModes(agentId, modes);
        return ResponseEntity.ok(mode);
    }

    @DeleteMapping("/custom/{mode_id}")
    public ResponseEntity<Void> deleteCustomMode(@PathVariable String mode_id,
                                                 HttpServletRequest request) {
        String agentId = resolveAgentId(request);
        List<Map<String, Object>> modes = customModes(agentId);
        int index = findMode(modes, mode_id);
        if (index < 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        modes.remove(index);
        persistCustomModes(agentId, modes);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/custom/{mode_id}/duplicate")
    public ResponseEntity<Map<String, Object>> duplicateCustomMode(@PathVariable String mode_id,
                                                                   HttpServletRequest request) {
        String agentId = resolveAgentId(request);
        List<Map<String, Object>> modes = customModes(agentId);
        int index = findMode(modes, mode_id);
        if (index < 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "Mode not found"));
        }
        Map<String, Object> source = modes.get(index);
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put("id", uniqueValue(SkillService.str(source.get("id")) + "-copy", modes, "id"));
        copy.put("name", uniqueValue(SkillService.str(source.get("name")) + " Copy", modes, "name"));
        copy.put("slash_command", uniqueValue(SkillService.str(source.get("slash_command")) + "-copy", modes, "slash_command"));
        copy.put("enabled", anyGateEnabled(source));
        modes.add(copy);
        persistCustomModes(agentId, modes);
        return ResponseEntity.status(HttpStatus.CREATED).body(copy);
    }

    private static int findMode(List<Map<String, Object>> modes, String modeId) {
        for (int i = 0; i < modes.size(); i++) {
            if (modeId.equals(SkillService.str(modes.get(i).get("id")))) {
                return i;
            }
        }
        return -1;
    }

    private static String uniqueValue(String base, List<Map<String, Object>> modes, String field) {
        String candidate = base;
        int suffix = 1;
        while (containsValue(modes, field, candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static boolean containsValue(List<Map<String, Object>> modes, String field, String value) {
        for (Map<String, Object> mode : modes) {
            if (value.equals(SkillService.str(mode.get(field)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyGateEnabled(Map<String, Object> mode) {
        Object gates = mode.get("gates");
        if (gates instanceof List<?> list) {
            for (Object gate : list) {
                if (gate instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("enabled"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
