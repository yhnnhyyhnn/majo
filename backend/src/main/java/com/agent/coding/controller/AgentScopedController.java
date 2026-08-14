package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.cron.CronModels;
import com.agent.coding.cron.CronManager;
import com.agent.coding.cron.CronExecutor;
import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.repository.ProviderRepository;
import com.agent.coding.skill.SkillNotFoundException;
import com.agent.coding.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent-scoped endpoints, ported from qwenpaw app/routers/agent_scoped.py.
 * These paths resolve the agent from the URL (rather than the X-Agent-Id
 * header used by the non-scoped variants).
 */
@RestController
@RequestMapping("/api/agents/{agentId}")
@CrossOrigin(origins = "*")
public class AgentScopedController {

    private static final Logger log = LoggerFactory.getLogger(AgentScopedController.class);
    private static final Map<String, CronManager> MANAGERS = new ConcurrentHashMap<>();

    private final ProviderRepository providerRepo;
    private final CronExecutor executor;

    public AgentScopedController(ProviderRepository providerRepo, CronExecutor executor) {
        this.providerRepo = providerRepo;
        this.executor = executor;
    }

    private void ensureAgent(String agentId) {
        if (!AgentStore.hasAgent(agentId)) {
            throw new SkillNotFoundException("Agent '" + agentId + "' not found");
        }
    }

    @GetMapping("/config/agents/llm-routing")
    public ResponseEntity<?> getLlmRouting(@PathVariable String agentId) {
        ensureAgent(agentId);
        Object routing = AgentStore.getProfile(agentId).get("llm_routing");
        if (routing == null) {
            return ResponseEntity.ok(new LinkedHashMap<>());
        }
        return ResponseEntity.ok(routing);
    }

    @PutMapping("/config/agents/llm-routing")
    public ResponseEntity<?> updateLlmRouting(@PathVariable String agentId,
                                              @RequestBody Map<String, Object> body) {
        ensureAgent(agentId);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("llm_routing", body);
        AgentStore.updateAgent(agentId, updates);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/cron/jobs")
    public ResponseEntity<?> createCronJob(@PathVariable String agentId,
                                           @RequestBody Map<String, Object> body) {
        ensureAgent(agentId);
        try {
            Map<String, Object> spec = CronModels.deepCopy(body);
            CronModels.validateSpec(spec);
            spec.put("id", UUID.randomUUID().toString());
            CronManager mgr = MANAGERS.computeIfAbsent(agentId, id -> new CronManager(
                    new com.agent.coding.cron.CronJobRepository(AgentStore.workspaceDirForAgent(id)),
                    executor, id));
            mgr.createOrReplaceJob(spec);
            return ResponseEntity.ok(spec);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(422).body(Map.of("detail", e.getMessage()));
        } catch (Exception e) {
            log.error("agent cron create failed", e);
            return ResponseEntity.status(500).body(Map.of("detail", String.valueOf(e.getMessage())));
        }
    }
}
