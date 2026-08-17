package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillNotFoundException;
import com.agent.coding.skill.SkillService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coding Mode toggle,.
 *
 * <p>State is persisted in the agent profile under {@code coding_mode}
 * ({@code enabled}, {@code project_dir}). The frontend calls this on agent
 * switch / app boot so the toggle state tracks the backend.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CodingModeController {

    private String resolveAgentId(HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getParameter("agent");
        }
        return (agentId == null || agentId.isBlank()) ? "default" : agentId;
    }

    @GetMapping("/coding-mode")
    public Map<String, Object> getCodingMode(HttpServletRequest request) {
        String agentId = resolveAgentId(request);
        Map<String, Object> profile = AgentStore.getProfile(agentId);
        if (profile == null) {
            throw new SkillNotFoundException("Agent '" + agentId + "' not found");
        }
        Map<String, Object> cm = SkillService.asMap(profile.get("coding_mode"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", SkillService.bool(cm.get("enabled"), false));
        result.put("project_dir", cm.get("project_dir"));
        result.put("agent_id", agentId);
        return result;
    }

    @PostMapping("/coding-mode")
    public Map<String, Object> setCodingMode(@RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        String agentId = resolveAgentId(request);
        Map<String, Object> profile = AgentStore.getProfile(agentId);
        if (profile == null) {
            throw new SkillNotFoundException("Agent '" + agentId + "' not found");
        }
        boolean enabled = SkillService.bool(body.get("enabled"), false);

        Map<String, Object> cm = SkillService.asMap(profile.get("coding_mode"));
        cm.put("enabled", enabled);
        if (body.containsKey("project_dir")) {
            Object projectDir = body.get("project_dir");
            if (projectDir == null) {
                cm.remove("project_dir");
            } else {
                cm.put("project_dir", SkillService.str(projectDir));
            }
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("coding_mode", cm);
        AgentStore.updateAgent(agentId, updates);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("agent_id", agentId);
        return result;
    }
}
