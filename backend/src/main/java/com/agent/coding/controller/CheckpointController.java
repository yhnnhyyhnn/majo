package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.service.CheckpointService;
import com.agent.coding.skill.SkillNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace checkpoint endpoints
 * app/routers/checkpoints.py. Snapshots are git commits in the workspace's
 * checkpoint repo (refs/auto|snap|pre-restore) with metadata in
 * .checkpoints/heads.json.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CheckpointController {

    private static final Logger log = LoggerFactory.getLogger(CheckpointController.class);

    private final CheckpointService service;

    public CheckpointController(CheckpointService service) {
        this.service = service;
    }

    private Path resolveWorkspace(HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getParameter("agent");
        }
        if (agentId != null && !agentId.isBlank() && AgentStore.hasAgent(agentId)) {
            return AgentStore.workspaceDirForAgent(agentId);
        }
        if (agentId != null && !agentId.isBlank()) {
            throw new SkillNotFoundException("Agent '" + agentId + "' not found");
        }
        return com.agent.coding.skill.SkillStore.WORKING_DIR;
    }

    @GetMapping("/workspace/checkpoints/status")
    public Map<String, Object> status(HttpServletRequest request) {
        Path ws = resolveWorkspace(request);
        try {
            List<Map<String, Object>> entries = service.graphEntries(ws);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("auto_enabled", false);
            result.put("has_checkpoints", !entries.isEmpty());
            result.put("workspace_dir", ws.toString());
            return result;
        } catch (IOException e) {
            throw new CheckpointErrorException("Failed to read checkpoints: " + e.getMessage());
        }
    }

    @PatchMapping("/workspace/checkpoints/auto")
    public Map<String, Object> setAuto(@RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return Map.of("auto_enabled", enabled);
    }

    @GetMapping("/workspace/checkpoints/graph")
    public Map<String, Object> graph(@RequestParam(defaultValue = "500") int limit,
                                     HttpServletRequest request) {
        Path ws = resolveWorkspace(request);
        try {
            List<Map<String, Object>> nodes = service.graphEntries(ws);
            if (nodes.size() > limit) {
                nodes = new ArrayList<>(nodes.subList(0, limit));
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", nodes.size());
            summary.put("auto", countKind(nodes, "auto"));
            summary.put("snapshots", countKind(nodes, "snap"));
            summary.put("safety", countKind(nodes, "pre-restore"));
            summary.put("heads", nodes.stream().filter(n -> Boolean.TRUE.equals(n.get("is_head"))).count());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodes", nodes);
            result.put("sessions", sessionList(ws));
            result.put("summary", summary);
            result.put("truncated", nodes.size() == limit);
            return result;
        } catch (IOException e) {
            throw new CheckpointErrorException("Failed to read checkpoints: " + e.getMessage());
        }
    }

    private static long countKind(List<Map<String, Object>> nodes, String kind) {
        return nodes.stream().filter(n -> kind.equals(n.get("kind"))).count();
    }

    private List<Map<String, Object>> sessionList(Path ws) {
        List<Map<String, Object>> sessions = new ArrayList<>();
        try {
            for (Map.Entry<String, String> e : service.loadHeads(ws).entrySet()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("session_key", e.getKey());
                s.put("session_id", "");
                s.put("user_id", "");
                s.put("channel", "console");
                s.put("title", "");
                s.put("archived", false);
                sessions.add(s);
            }
        } catch (Exception ignored) {
        }
        return sessions;
    }

    @PostMapping("/workspace/checkpoints/snapshot")
    public Map<String, Object> snapshot(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        Path ws = resolveWorkspace(request);
        String sessionId = str(body.get("session_id"));
        if (sessionId.isBlank()) {
            throw new CheckpointErrorException("session_id is required");
        }
        String userId = str(body.get("user_id"));
        String channel = str(body.get("channel"));
        if (channel.isBlank()) channel = "console";
        String name = str(body.get("name"));
        try {
            return service.makeSnapshot(ws, "snap", sessionId, userId, channel, name, name);
        } catch (IOException e) {
            throw new CheckpointErrorException("Snapshot failed: " + e.getMessage());
        }
    }

    @PostMapping("/workspace/checkpoints/restore/preview")
    public Map<String, Object> previewRestore(@RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        return doRestore(body, request, true);
    }

    @PostMapping("/workspace/checkpoints/restore")
    public Map<String, Object> applyRestore(@RequestBody Map<String, Object> body,
                                            HttpServletRequest request) {
        boolean includeFiles = Boolean.TRUE.equals(body.get("include_files"));
        Object files = body.get("files");
        if (includeFiles && (files == null || (files instanceof List<?> l && l.isEmpty()))) {
            throw new CheckpointErrorException("Select at least one file before restoring files.");
        }
        return doRestore(body, request, false);
    }

    private Map<String, Object> doRestore(Map<String, Object> body, HttpServletRequest request,
                                          boolean dryRun) {
        Path ws = resolveWorkspace(request);
        String commit = str(body.get("commit"));
        if (commit.length() < 7) {
            throw new CheckpointErrorException("commit is required (min 7 chars)");
        }
        boolean includeMemory = Boolean.TRUE.equals(body.get("include_memory"));
        boolean includeFiles = Boolean.TRUE.equals(body.get("include_files"));
        try {
            List<String> changed = service.changedPaths(ws, commit);
            if (!dryRun) {
                if (includeMemory) {
                    changed = service.restoreFiles(ws, commit);
                }
                if (includeFiles) {
                    @SuppressWarnings("unchecked")
                    List<String> selected = (List<String>) body.getOrDefault("files", List.of());
                    if (selected.isEmpty()) {
                        changed = service.restoreFiles(ws, commit);
                    } else {
                        service.restoreFiles(ws, commit);
                    }
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("target", commit);
            result.put("commit", commit);
            result.put("restored_paths", changed);
            result.put("deleted_paths", List.of());
            result.put("file_paths", includeFiles ? List.of() : List.of());
            result.put("pre_restore_ref", null);
            result.put("dry_run", dryRun);
            result.put("include_memory", includeMemory);
            result.put("include_files", includeFiles);
            return result;
        } catch (IOException e) {
            throw new CheckpointErrorException("Restore failed: " + e.getMessage());
        }
    }

    @PostMapping("/workspace/checkpoints/gc/preview")
    public Map<String, Object> previewGc(@RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest request) {
        return runGc(body, request, true);
    }

    @PostMapping("/workspace/checkpoints/gc")
    public Map<String, Object> applyGc(@RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest request) {
        return runGc(body, request, false);
    }

    private Map<String, Object> runGc(Map<String, Object> body, HttpServletRequest request,
                                      boolean dryRun) {
        Path ws = resolveWorkspace(request);
        body = body == null ? Map.of() : body;
        Integer keepCount = intOrNull(body.get("keep_count"));
        Integer keepDays = intOrNull(body.get("keep_days"));
        try {
            List<String> deleted = service.gc(ws, dryRun, keepCount, keepDays);
            List<String> kept = new ArrayList<>();
            for (String ref : service.listRefs(ws).keySet()) {
                kept.add(ref);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deleted_refs", deleted);
            result.put("kept_refs", kept);
            result.put("dry_run", dryRun);
            return result;
        } catch (IOException e) {
            throw new CheckpointErrorException("GC failed: " + e.getMessage());
        }
    }

    @GetMapping("/workspace/checkpoints/gc/settings")
    public Map<String, Object> gcSettings(HttpServletRequest request) {
        return service.gcSettings(resolveWorkspace(request));
    }

    @PatchMapping("/workspace/checkpoints/gc/settings")
    public Map<String, Object> updateGcSettings(@RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        int keepCount = intOf(body.get("gc_keep_count"), 20);
        int keepDays = intOf(body.get("gc_keep_days"), 30);
        int preRestoreDays = intOf(body.get("pre_restore_retention_days"), 7);
        try {
            service.saveGcSettings(resolveWorkspace(request), keepCount, keepDays, preRestoreDays);
        } catch (IOException e) {
            throw new CheckpointErrorException("Failed to save GC settings: " + e.getMessage());
        }
        return Map.of(
                "gc_keep_count", keepCount,
                "gc_keep_days", keepDays,
                "pre_restore_retention_days", preRestoreDays);
    }

    @DeleteMapping("/workspace/checkpoints")
    public Map<String, Object> reset(HttpServletRequest request) {
        try {
            service.reset(resolveWorkspace(request));
        } catch (IOException e) {
            throw new CheckpointErrorException("Reset failed: " + e.getMessage());
        }
        return Map.of("reset", true, "auto_enabled", false);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static int intOf(Object o, int def) {
        Integer v = intOrNull(o);
        return v == null ? def : v;
    }

    /** 400 + {"detail": ...} body, matching FastAPI HTTPException. */
    public static class CheckpointErrorException extends RuntimeException {
        public CheckpointErrorException(String detail) {
            super(detail);
        }

        public String getDetail() {
            return getMessage();
        }
    }

    @ExceptionHandler(CheckpointErrorException.class)
    public ResponseEntity<Map<String, Object>> handleCheckpointError(CheckpointErrorException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getDetail()));
    }
}
