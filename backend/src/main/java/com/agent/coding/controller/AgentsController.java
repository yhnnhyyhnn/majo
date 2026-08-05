package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.dto.*;
import com.agent.coding.skill.SkillPoolService;
import com.agent.coding.skill.SkillRegistry;
import com.agent.coding.skill.SkillStore;
import com.agent.coding.skill.SkillsError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Multi-agent management API ({@code /api/agents}).
 *
 * Backed by {@code WORKING_DIR/agents.json} (AgentStore).  Mirrors the
 * qwenpaw agents router contract consumed by the frontend
 * (frontend/src/api/modules/agents.ts).
 */
@RestController
@RequestMapping("/api/agents")
@CrossOrigin(origins = "*")
public class AgentsController {

    private static final Logger log = LoggerFactory.getLogger(AgentsController.class);

    // ------------------------------------------------------------------
    // List / get
    // ------------------------------------------------------------------

    @GetMapping("")
    public AgentListResponse listAgents() {
        List<AgentInfo> agents = new ArrayList<>();
        for (Map<String, Object> profile : AgentStore.listProfiles()) {
            agents.add(toAgentInfo(profile));
        }
        return new AgentListResponse(agents);
    }

    @GetMapping("/{agentId}")
    public AgentProfileConfig getAgent(@PathVariable String agentId) {
        Map<String, Object> profile = AgentStore.getProfile(agentId);
        if (profile == null) {
            throw new SkillsError("Agent '" + agentId + "' not found");
        }
        return toAgentProfileConfig(profile);
    }

    // ------------------------------------------------------------------
    // Create / copy
    // ------------------------------------------------------------------

    @PostMapping("")
    public ResponseEntity<AgentProfileRef> createAgent(@RequestBody CreateAgentRequest request) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", request.getId());
        spec.put("name", request.getName());
        spec.put("description", request.getDescription());
        spec.put("workspace_dir", request.getWorkspaceDir());
        spec.put("language", request.getLanguage());
        spec.put("backend", request.getBackend());
        spec.put("active_model", request.getActiveModel());

        Map<String, Object> profile = AgentStore.createAgent(spec);

        installInitialSkills(profile, request.getSkillNames());

        log.info("Created agent: {} (name={})", profile.get("id"), request.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(toAgentProfileRef(profile));
    }

    @PostMapping("/{agentId}/copy")
    public ResponseEntity<AgentProfileRef> copyAgent(
            @PathVariable String agentId,
            @RequestBody(required = false) CopyAgentRequest request) {
        Map<String, Object> source = AgentStore.getProfile(agentId);
        if (source == null) {
            throw new SkillsError("Agent '" + agentId + "' not found");
        }
        CopyAgentRequest req = request != null ? request : new CopyAgentRequest();

        String newId = generateUniqueId();
        String name = (req.getName() == null || req.getName().isBlank())
                ? SkillServiceStr.of(source.get("name"), agentId) + " Copy"
                : req.getName().trim();

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", newId);
        spec.put("name", name);
        spec.put("description", source.get("description"));
        spec.put("backend", source.get("backend"));
        spec.put("language", source.get("language"));
        spec.put("active_model", source.get("active_model"));

        Map<String, Object> profile = AgentStore.createAgent(spec);
        Path targetWorkspace = Path.of(SkillServiceStr.of(profile.get("workspace_dir"), ""));

        if (Boolean.TRUE.equals(req.getCopySkills())) {
            copySkills(SkillRegistry.workspaceDirForAgent(agentId), targetWorkspace);
        }

        log.info("Copied agent {} -> {} (name={})", agentId, newId, name);
        return ResponseEntity.status(HttpStatus.CREATED).body(toAgentProfileRef(profile));
    }

    // ------------------------------------------------------------------
    // Update / order / toggle / pin
    // ------------------------------------------------------------------

    @PutMapping("/{agentId}")
    public AgentProfileConfig updateAgent(@PathVariable String agentId,
                                          @RequestBody AgentProfileConfig body) {
        Map<String, Object> updates = new LinkedHashMap<>();
        if (body.getName() != null) updates.put("name", body.getName());
        if (body.getDescription() != null) updates.put("description", body.getDescription());
        if (body.getBackend() != null) updates.put("backend", body.getBackend());
        if (body.getLanguage() != null) updates.put("language", body.getLanguage());
        updates.put("active_model", body.getActiveModel());

        Map<String, Object> profile = AgentStore.updateAgent(agentId, updates);
        return toAgentProfileConfig(profile);
    }

    @PutMapping("/order")
    public Map<String, Object> reorderAgents(@RequestBody ReorderAgentsRequest request) {
        AgentStore.setAgentOrder(request.getAgentIds());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("agent_ids", request.getAgentIds());
        return result;
    }

    @PatchMapping("/{agentId}/toggle")
    public Map<String, Object> toggleAgentEnabled(@PathVariable String agentId,
                                                  @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        AgentStore.setAgentEnabled(agentId, enabled);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("agent_id", agentId);
        result.put("enabled", enabled);
        return result;
    }

    @PatchMapping("/{agentId}/pin")
    public Map<String, Object> setAgentPinned(@PathVariable String agentId,
                                              @RequestBody Map<String, Object> body) {
        boolean pinned = Boolean.TRUE.equals(body.get("pinned"));
        AgentStore.setAgentPinned(agentId, pinned);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("agent_id", agentId);
        result.put("pinned", pinned);
        return result;
    }

    @PatchMapping("/{agentId}/backend-settings")
    public AgentProfileConfig updateBackendSettings(@PathVariable String agentId,
                                                    @RequestBody Map<String, Object> body) {
        Map<String, Object> profile = AgentStore.getProfile(agentId);
        if (profile == null) {
            throw new SkillsError("Agent '" + agentId + "' not found");
        }
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("backend_settings", body);
        AgentStore.updateAgent(agentId, updates);
        return toAgentProfileConfig(AgentStore.getProfile(agentId));
    }

    // ------------------------------------------------------------------
    // Memory reindex (stub - no memory index yet)
    // ------------------------------------------------------------------

    @PostMapping("/{agentId}/memory/reindex")
    public Map<String, Object> rebuildMemoryIndex(@PathVariable String agentId) {
        Map<String, Object> profile = AgentStore.getProfile(agentId);
        if (profile == null) {
            throw new SkillsError("Agent '" + agentId + "' not found");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        return result;
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    @DeleteMapping("/{agentId}")
    public Map<String, Object> deleteAgent(@PathVariable String agentId) {
        AgentStore.deleteAgent(agentId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("agent_id", agentId);
        return result;
    }

    // ------------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------------

    private AgentInfo toAgentInfo(Map<String, Object> profile) {
        String id = SkillServiceStr.of(profile.get("id"), "");
        boolean enabled = Boolean.TRUE.equals(profile.get("enabled"));
        boolean pinned = id.equals(AgentStore.DEFAULT_AGENT_ID)
                || Boolean.TRUE.equals(profile.get("pinned"));
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("workspace_ui", true);
        caps.put("code_files", true);
        AgentInfo info = new AgentInfo(
                id,
                SkillServiceStr.of(profile.get("name"), id),
                SkillServiceStr.of(profile.get("description"), ""),
                SkillServiceStr.of(profile.get("workspace_dir"), ""),
                enabled,
                enabled ? "running" : "disabled",
                SkillServiceStr.of(profile.get("backend"), "majo"),
                caps);
        info.setPinned(pinned);
        info.setActiveModel(profile.get("active_model"));
        Object backendSettings = profile.get("backend_settings");
        if (backendSettings instanceof Map<?, ?> bs) {
            Object model = bs.get("model");
            Object effort = bs.get("reasoning_effort");
            info.setBackendModel(model != null ? String.valueOf(model) : null);
            info.setBackendReasoningEffort(effort != null ? String.valueOf(effort) : null);
        }
        return info;
    }

    private AgentProfileConfig toAgentProfileConfig(Map<String, Object> profile) {
        String id = SkillServiceStr.of(profile.get("id"), "");
        return new AgentProfileConfig(
                id,
                SkillServiceStr.of(profile.get("name"), id),
                SkillServiceStr.of(profile.get("description"), ""),
                SkillServiceStr.of(profile.get("workspace_dir"), ""),
                SkillServiceStr.of(profile.get("backend"), "majo"),
                profile.get("backend_settings"),
                SkillServiceStr.of(profile.get("language"), "zh"),
                profile.get("active_model"));
    }

    private AgentProfileRef toAgentProfileRef(Map<String, Object> profile) {
        String id = SkillServiceStr.of(profile.get("id"), "");
        return new AgentProfileRef(
                id,
                SkillServiceStr.of(profile.get("workspace_dir"), ""),
                Boolean.TRUE.equals(profile.get("enabled")),
                Boolean.TRUE.equals(profile.get("pinned")));
    }

    private void installInitialSkills(Map<String, Object> profile, List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) return;
        String workspaceDir = SkillServiceStr.of(profile.get("workspace_dir"), "");
        if (workspaceDir.isBlank()) return;
        Path workspace = Path.of(workspaceDir);
        SkillPoolService pool = new SkillPoolService();
        for (String skillName : skillNames) {
            try {
                Map<String, Object> result = pool.downloadToWorkspace(skillName, workspace, false);
                if (Boolean.TRUE.equals(result.get("success"))) continue;
                log.warn("Failed to install initial skill {} for {}: {}",
                        skillName, profile.get("id"), result.get("reason"));
            } catch (Exception e) {
                log.warn("Failed to install initial skill {} for {}: {}",
                        skillName, profile.get("id"), e.getMessage());
            }
        }
    }

    private void copySkills(Path sourceWorkspace, Path targetWorkspace) {
        Path srcSkills = SkillStore.getWorkspaceSkillsDir(sourceWorkspace);
        if (!Files.isDirectory(srcSkills)) return;
        try {
            Path dstSkills = SkillStore.getWorkspaceSkillsDir(targetWorkspace);
            Files.createDirectories(dstSkills);
            try (var stream = Files.walk(srcSkills)) {
                stream.forEach(src -> {
                    try {
                        Path rel = srcSkills.relativize(src);
                        Path dst = dstSkills.resolve(rel);
                        if (Files.isDirectory(src)) {
                            Files.createDirectories(dst);
                        } else {
                            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
            Path srcManifest = sourceWorkspace.resolve("skill.json");
            if (Files.isRegularFile(srcManifest)) {
                Files.copy(srcManifest, targetWorkspace.resolve("skill.json"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Failed to copy skills from {} to {}", sourceWorkspace, targetWorkspace, e);
        }
    }

    private String generateUniqueId() {
        int maxAttempts = 10;
        for (int i = 0; i < maxAttempts; i++) {
            String candidate = "agent-" + UUID.randomUUID().toString().substring(0, 8);
            if (!AgentStore.hasAgent(candidate)) {
                return candidate;
            }
        }
        throw new SkillsError("Failed to generate unique agent ID after " + maxAttempts + " attempts");
    }

    /** Minimal null-safe string helper (avoids repeating valueOf boilerplate). */
    private static final class SkillServiceStr {
        static String of(Object value) {
            return value == null ? "" : String.valueOf(value);
        }
        static String of(Object value, String fallback) {
            String s = of(value);
            return s.isBlank() ? fallback : s;
        }
    }
}
