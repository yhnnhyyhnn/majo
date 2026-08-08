package com.agent.coding.controller;

import com.agent.coding.ChatService;
import com.agent.coding.agent.AgentStore;
import com.agent.coding.cron.CronJobRepository;
import com.agent.coding.cron.CronManager;
import com.agent.coding.cron.CronModels;
import com.agent.coding.cron.CronExecutor;
import com.agent.coding.entity.ChatEntity;
import com.agent.coding.inbox.InboxStore;
import com.agent.coding.service.ModelRoutingService;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cron job API, ported from qwenpaw app/crons/api.py (prefix /cron).
 *
 * <p>Jobs are stored per-agent under the agent workspace
 * ({@code <workspace>/jobs.json}), matching qwenpaw's per-workspace
 * repository. Endpoint shapes and error semantics follow the reference:
 * create ignores the client id and generates a UUID; validation failures
 * return 422 with a {@code detail} message the frontend can translate.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CronController {

    private static final Logger log = LoggerFactory.getLogger(CronController.class);
    private static final String DEFAULT_AGENT = "default";

    private final ChatService chatService;
    private final InboxStore inboxStore;
    private final CronExecutor executor;

    /** Per-agent CronManager instances (each owns a scheduler thread pool). */
    private static final java.util.Map<String, CronManager> MANAGERS = new java.util.concurrent.ConcurrentHashMap<>();

    public CronController(ChatService chatService, InboxStore inboxStore, CronExecutor executor) {
        this.chatService = chatService;
        this.inboxStore = inboxStore;
        this.executor = executor;
    }

    // ------------------------------------------------------------------
    // Agent resolution
    // ------------------------------------------------------------------

    private static String resolveAgentId(String headerAgentId, Map<String, Object> body) {
        if (headerAgentId != null && !headerAgentId.isBlank()) return headerAgentId;
        Object bodyAgent = body == null ? null : body.get("agent_id");
        if (bodyAgent instanceof String s && !s.isBlank()) return s;
        return DEFAULT_AGENT;
    }

    private CronManager managerFor(String agentId) {
        String key = agentId == null || agentId.isBlank() ? "default" : agentId;
        return MANAGERS.computeIfAbsent(key, id -> {
            CronManager mgr = new CronManager(repoFor(id), executor, id);
                return mgr;
        });
    }

    private CronJobRepository repoFor(String agentId) {
        Path ws;
        try {
            ws = AgentStore.workspaceDirForAgent(agentId);
        } catch (Exception e) {
            ws = AgentStore.defaultWorkspaceDir();
        }
        return new CronJobRepository(ws);
    }

    // ------------------------------------------------------------------
    // Dispatch targets
    // ------------------------------------------------------------------

    @GetMapping("/cron/dispatch-targets")
    public Map<String, Object> listDispatchTargets(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "500") int limit,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestParam(required = false) String agent_id) {
        String agentId = agentIdHeader != null && !agentIdHeader.isBlank()
                ? agentIdHeader : (agent_id != null ? agent_id : DEFAULT_AGENT);
        String kw = (keyword == null ? "" : keyword).trim().toLowerCase();
        List<ChatEntity> chats;
        try {
            chats = chatService.listByAgent(agentId, null);
        } catch (Exception e) {
            chats = chatService.list(null, channel, null);
        }
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (ChatEntity chat : chats) {
            if (channel != null && !channel.isBlank() && !channel.equals(chat.getChannel())) {
                continue;
            }
            String c = chat.getChannel() == null ? "console" : chat.getChannel();
            String u = chat.getUserId() == null ? "" : chat.getUserId();
            String s = chat.getSessionId() == null ? "" : chat.getSessionId();
            if (!kw.isEmpty()) {
                String haystack = (c + " " + u + " " + s).toLowerCase();
                if (!haystack.contains(kw)) continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channel", c);
            item.put("user_id", u);
            item.put("session_id", s);
            deduped.put(c + "\u0000" + u + "\u0000" + s, item);
            if (deduped.size() >= limit) break;
        }
        List<Map<String, Object>> items = new ArrayList<>(deduped.values());
        List<String> channels = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String c = String.valueOf(item.get("channel"));
            if (!channels.contains(c)) channels.add(c);
        }
        channels.sort(String::compareTo);
        if (!channels.contains("console")) channels.add(0, "console");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("channels", channels);
        resp.put("items", items);
        return resp;
    }

    // ------------------------------------------------------------------
    // Jobs CRUD
    // ------------------------------------------------------------------

    @GetMapping("/cron/jobs")
    public List<Map<String, Object>> listJobs(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestParam(required = false) String agent_id) {
        String agentId = (agentIdHeader != null && !agentIdHeader.isBlank())
                ? agentIdHeader : (agent_id != null ? agent_id : DEFAULT_AGENT);
        CronManager mgr = managerFor(agentId);
        return mgr.listJobs();
    }

    @PostMapping("/cron/jobs")
    public ResponseEntity<?> createJob(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader) {
        String agentId = resolveAgentId(agentIdHeader, body);
        try {
            Map<String, Object> spec = CronModels.deepCopy(body);
            CronModels.validateSpec(spec);
            spec.put("id", UUID.randomUUID().toString());
            CronManager mgr = managerFor(agentId);
                mgr.createOrReplaceJob(spec);
            return ResponseEntity.ok(spec);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(422).body(Map.of("detail", e.getMessage()));
        } catch (Exception e) {
            log.error("cron create failed", e);
            return ResponseEntity.status(500).body(Map.of("detail", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/cron/jobs/{job_id}")
    public ResponseEntity<?> getJob(@PathVariable String job_id,
                                    @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader) {
        String agentId = resolveAgentId(agentIdHeader, Map.of());
        CronManager mgr = managerFor(agentId);
        Map<String, Object> job = mgr.getJob(job_id);
        if (job == null) {
            return ResponseEntity.status(404).body(Map.of("detail", "job not found"));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("spec", job);
        view.put("state", mgr.getState(job_id));
        return ResponseEntity.ok(view);
    }

    @PutMapping("/cron/jobs/{job_id}")
    public ResponseEntity<?> replaceJob(@PathVariable String job_id,
                                        @RequestBody Map<String, Object> body,
                                        @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader) {
        String agentId = resolveAgentId(agentIdHeader, body);
        try {
            Object bodyId = body.get("id");
            if (bodyId == null) {
                body.put("id", job_id);
            } else if (!job_id.equals(String.valueOf(bodyId))) {
                return ResponseEntity.badRequest().body(Map.of("detail", "job_id mismatch"));
            }
            Map<String, Object> spec = CronModels.deepCopy(body);
            CronModels.validateSpec(spec);
            spec.put("id", job_id);
            CronManager mgr = managerFor(agentId);
                mgr.createOrReplaceJob(spec);
            return ResponseEntity.ok(spec);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(422).body(Map.of("detail", e.getMessage()));
        } catch (Exception e) {
            log.error("cron replace failed", e);
            return ResponseEntity.status(500).body(Map.of("detail", String.valueOf(e.getMessage())));
        }
    }

    @DeleteMapping("/cron/jobs/{job_id}")
    public ResponseEntity<?> deleteJob(@PathVariable String job_id,
                                       @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader) {
        String agentId = resolveAgentId(agentIdHeader, Map.of());
        CronManager mgr = managerFor(agentId);
        boolean ok = mgr.deleteJob(job_id);
        if (!ok) {
            return ResponseEntity.status(404).body(Map.of("detail", "job not found"));
        }
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------

    @PostMapping("/cron/jobs/{job_id}/pause")
    public ResponseEntity<?> pauseJob(@PathVariable String job_id,
                                      @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader) {
        try {
            CronManager mgr = managerFor(resolveAgentId(agentIdHeader, Map.of()));
                if (mgr.getJob(job_id) == null) {
                return ResponseEntity.status(404).body(Map.of("detail", "job not found"));
            }
            mgr.pauseJob(job_id);
            return ResponseEntity.ok(Map.of("paused", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/cron/jobs/{job_id}/resume")
    public ResponseEntity<?> resumeJob(@PathVariable String job_id,
                                       @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader) {
        try {
            CronManager mgr = managerFor(resolveAgentId(agentIdHeader, Map.of()));
                if (mgr.getJob(job_id) == null) {
                return ResponseEntity.status(404).body(Map.of("detail", "job not found"));
            }
            mgr.resumeJob(job_id);
            return ResponseEntity.ok(Map.of("resumed", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/cron/jobs/{job_id}/run")
    public ResponseEntity<?> runJob(@PathVariable String job_id,
                                    @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader) {
        try {
            CronManager mgr = managerFor(resolveAgentId(agentIdHeader, Map.of()));
                mgr.runJob(job_id);
            return ResponseEntity.ok(Map.of("started", true));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("detail", "job not found"));
        } catch (Exception e) {
            log.error("cron run failed", e);
            return ResponseEntity.status(500).body(Map.of("detail", e.getMessage()));
        }
    }

    @GetMapping("/cron/jobs/{job_id}/state")
    public ResponseEntity<?> getJobState(@PathVariable String job_id,
                                         @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader) {
        String agentId = resolveAgentId(agentIdHeader, Map.of());
        CronManager mgr = managerFor(agentId);
        if (mgr.getJob(job_id) == null) {
            return ResponseEntity.status(404).body(Map.of("detail", "job not found"));
        }
        return ResponseEntity.ok(mgr.getState(job_id));
    }

    @GetMapping("/cron/jobs/{job_id}/history")
    public ResponseEntity<?> getJobHistory(@PathVariable String job_id,
                                           @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader) {
        String agentId = resolveAgentId(agentIdHeader, Map.of());
        CronManager mgr = managerFor(agentId);
        if (mgr.getJob(job_id) == null) {
            return ResponseEntity.status(404).body(Map.of("detail", "job not found"));
        }
        return ResponseEntity.ok(mgr.getHistory(job_id));
    }
}
