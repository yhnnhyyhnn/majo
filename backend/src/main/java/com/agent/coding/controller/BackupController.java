package com.agent.coding.controller;

import com.agent.coding.backup.BackupCreator;
import com.agent.coding.backup.BackupImporter;
import com.agent.coding.backup.BackupMeta;
import com.agent.coding.backup.BackupRestorer;
import com.agent.coding.backup.BackupStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backup API,. Backups are stored as
 * zip archives under {@code WORKING_DIR/.backups}.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class BackupController {

    private static final Logger log = LoggerFactory.getLogger(BackupController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ===== List =====

    @GetMapping("/backups")
    public List<BackupMeta> listBackups() {
        return BackupStore.listBackups();
    }

    // ===== Detail =====

    @GetMapping("/backups/{backup_id}")
    public ResponseEntity<?> getBackup(@PathVariable String backup_id) {
        BackupMeta meta = BackupStore.getBackup(backup_id);
        if (meta == null) {
            return ResponseEntity.status(404).body(Map.of("detail", "Backup not found"));
        }
        meta.signature = null;
        return ResponseEntity.ok(meta);
    }

    // ===== Delete =====

    @PostMapping("/backups/delete")
    public Map<String, Object> deleteBackups(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.getOrDefault("ids", List.of());
        BackupStore.DeleteResult result = BackupStore.deleteBackups(ids);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deleted", result.deleted);
        resp.put("failed", result.failed);
        return resp;
    }

    // ===== Export =====

    @GetMapping("/backups/{backup_id}/export")
    public ResponseEntity<?> exportBackup(@PathVariable String backup_id) {
        try {
            Path zip = BackupStore.exportBackup(backup_id);
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + backup_id + ".zip\"")
                .body(new FileSystemResource(zip));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("detail", "Backup not found"));
        }
    }

    // ===== Create (SSE stream, matches Majo format) =====

    @PostMapping(value = "/backups/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> createBackupStream(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", ""));
        String description = String.valueOf(body.getOrDefault("description", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> scopeMap = (Map<String, Object>) body.getOrDefault("scope", Map.of());
        @SuppressWarnings("unchecked")
        List<String> agents = (List<String>) body.getOrDefault("agents", List.of());

        BackupMeta.Scope scope = new BackupMeta.Scope(
            bool(scopeMap.get("include_agents"), true),
            bool(scopeMap.get("include_global_config"), true),
            bool(scopeMap.get("include_secrets"), false),
            bool(scopeMap.get("include_skill_pool"), true));

        StreamingResponseBody stream = out -> {
            Thread worker = new Thread(() -> {
                try {
                    BackupCreator.create(scope, agents, name, description, event -> {
                        writeSse(out, event);
                    });
                } catch (Throwable e) {
                    log.error("Backup creation failed", e);
                    try {
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("type", "error");
                        error.put("message", e.getMessage());
                        writeSse(out, error);
                    } catch (IOException ignored) {
                    }
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }, "backup-create");
            worker.start();

            // Keep the SSE connection alive while the worker compresses/signs
            // (a long silent gap makes proxies and Spring drop the stream, so
            // the final "done" event would never reach the client).
            while (worker.isAlive()) {
                try {
                    out.write(": heartbeat\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(5000);
                } catch (IOException | InterruptedException e) {
                    break;
                }
            }
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(stream);
    }

    private static void writeSse(java.io.OutputStream out, Map<String, Object> event) throws IOException {
        byte[] bytes = ("data: " + pythonJson(event) + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
    }

    /** Serialize a map exactly like Python {@code json.dumps} (", " and ": " separators). */
    private static String pythonJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BackupMeta meta) {
            return pythonJson(metaToMap(meta));
        }
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Number n) {
            return n.toString();
        }
        if (value instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\": ").append(pythonJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : it) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(pythonJson(item));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    /** Convert BackupMeta to an insertion-ordered mapmodel_dump field order. */
    private static Map<String, Object> metaToMap(BackupMeta meta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", meta.id);
        m.put("name", meta.name);
        m.put("description", meta.description);
        m.put("created_at", meta.createdAt);
        m.put("version", meta.version);
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("include_agents", meta.scope.include_agents);
        scope.put("include_global_config", meta.scope.include_global_config);
        scope.put("include_secrets", meta.scope.include_secrets);
        scope.put("include_skill_pool", meta.scope.include_skill_pool);
        m.put("scope", scope);
        m.put("agent_count", meta.agentCount);
        m.put("qwenpaw_version", meta.qwenpawVersion);
        m.put("system_info", meta.systemInfo == null ? Map.of() : meta.systemInfo);
        m.put("signature", meta.signature);
        m.put("accepted_via_trust", meta.acceptedViaTrust);
        return m;
    }

    private static boolean bool(Object value, boolean def) {
        if (value == null) {
            return def;
        }
        return Boolean.TRUE.equals(value);
    }

    // ===== Restore =====

    @PostMapping("/backups/{backup_id}/restore")
    public ResponseEntity<?> restoreBackup(@PathVariable String backup_id,
                                           @RequestBody Map<String, Object> body) {
        BackupRestorer.RestoreRequest req = new BackupRestorer.RestoreRequest();
        req.include_agents = bool(body.get("include_agents"), true);
        @SuppressWarnings("unchecked")
        List<String> agentIds = (List<String>) body.getOrDefault("agent_ids", List.of());
        req.agent_ids = agentIds;
        req.include_global_config = bool(body.get("include_global_config"), true);
        req.include_secrets = bool(body.get("include_secrets"), false);
        req.include_skill_pool = bool(body.get("include_skill_pool"), true);
        Object defaultWs = body.get("default_workspace_dir");
        req.default_workspace_dir = defaultWs == null ? null : String.valueOf(defaultWs);
        Object mode = body.get("mode");
        req.mode = mode == null ? "custom" : String.valueOf(mode);
        Object preserve = body.get("preserve_local_protected_config");
        req.preserve_local_protected_config = preserve == null ? null : Boolean.TRUE.equals(preserve);
        Object trust = body.get("trust_mode");
        req.trust_mode = trust == null ? null : String.valueOf(trust);

        try {
            BackupRestorer.preflight(backup_id, req);
            BackupRestorer.RestoreResponse resp = BackupRestorer.restore(backup_id, req);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("not found")) {
                return ResponseEntity.status(404).body(Map.of("detail", "Backup not found"));
            }
            return ResponseEntity.badRequest().body(Map.of("detail", msg));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("detail", String.valueOf(e.getMessage())));
        }
    }

    // ===== Import =====

    @PostMapping("/backups/import")
    public ResponseEntity<?> importBackup(@RequestParam(value = "file", required = false) MultipartFile file,
                                          @RequestParam(value = "pending_token", required = false) String pendingToken,
                                          @RequestParam(value = "trust_mode", required = false) String trustMode) {
        try {
            if (pendingToken != null && !pendingToken.isBlank()) {
                return handlePendingImport(pendingToken);
            }
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("detail", "file is required"));
            }
            String contentType = file.getContentType();
            if (contentType != null && !contentType.isBlank()
                    && !List.of("application/zip", "application/x-zip-compressed", "application/octet-stream")
                        .contains(contentType)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "detail", "Expected a zip file, got content-type: " + contentType));
            }
            Path dir = BackupStore.backupDir();
            Files.createDirectories(dir);
            String suffix = "legacy".equals(trustMode) ? ".upload_tmp.trust_legacy"
                : "foreign".equals(trustMode) ? ".upload_tmp.trust_foreign"
                : ".upload_tmp";
            Path tmp = Files.createTempFile(dir, "upload-", suffix);
            file.transferTo(tmp);

            try {
                BackupMeta meta = BackupImporter.importBackup(tmp, false, trustMode);
                Files.deleteIfExists(tmp);
                meta.signature = null;
                return ResponseEntity.ok(meta);
            } catch (BackupImporter.ConflictException e) {
                // Keep tmp file for pending_token retry.
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("detail", "backup_conflict");
                conflict.put("existing", e.existing);
                conflict.put("pending_token", tmp.getFileName().toString());
                return ResponseEntity.status(409).body(conflict);
            } catch (BackupImporter.ValidationException e) {
                Files.deleteIfExists(tmp);
                return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
            }
        } catch (Exception e) {
            log.error("Backup import failed", e);
            return ResponseEntity.status(500).body(Map.of("detail", String.valueOf(e.getMessage())));
        }
    }

    private ResponseEntity<?> handlePendingImport(String pendingToken) {
        Path tmp = BackupStore.backupDir().resolve(pendingToken).normalize();
        if (!tmp.startsWith(BackupStore.backupDir().toAbsolutePath().normalize())) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Invalid pending_token"));
        }
        if (!Files.isRegularFile(tmp)) {
            return ResponseEntity.status(404).body(Map.of("detail", "Pending upload not found"));
        }
        try {
            // The suffix carries the original trust mode (legacy/foreign).
            String trustMode = null;
            String name = tmp.getFileName().toString();
            if (name.contains("trust_legacy")) {
                trustMode = "legacy";
            } else if (name.contains("trust_foreign")) {
                trustMode = "foreign";
            }
            BackupMeta meta = BackupImporter.importBackup(tmp, true, trustMode);
            Files.deleteIfExists(tmp);
            meta.signature = null;
            return ResponseEntity.ok(meta);
        } catch (BackupImporter.ConflictException e) {
            return ResponseEntity.status(409).body(Map.of("detail", "backup_conflict"));
        } catch (BackupImporter.ValidationException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("detail", String.valueOf(e.getMessage())));
        }
    }
}
