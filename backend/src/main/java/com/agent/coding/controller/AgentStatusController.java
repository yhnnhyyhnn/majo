package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.service.TaskTracker;
import com.agent.coding.skill.SkillNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent runtime status,.
 * Reports idle / running / disabled with running task count and timestamps.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/agent-status")
@CrossOrigin(origins = "*")
public class AgentStatusController {

    private final TaskTracker taskTracker;

    public AgentStatusController(TaskTracker taskTracker) {
        this.taskTracker = taskTracker;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> status(@PathVariable String agentId) {
        if (!AgentStore.hasAgent(agentId)) {
            throw new SkillNotFoundException("Agent '" + agentId + "' not found");
        }
        Map<String, Object> profile = AgentStore.getProfile(agentId);
        boolean enabled = profile.get("enabled") == null || Boolean.TRUE.equals(profile.get("enabled"));
        if (!enabled) {
            Map<String, Object> disabled = new LinkedHashMap<>();
            disabled.put("status", "disabled");
            disabled.put("running_task_count", 0);
            disabled.put("last_run_at", null);
            disabled.put("last_finish_at", null);
            return ResponseEntity.ok(disabled);
        }
        return ResponseEntity.ok(taskTracker.getGlobalStatus());
    }
}
