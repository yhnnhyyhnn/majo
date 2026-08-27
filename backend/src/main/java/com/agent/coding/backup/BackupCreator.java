package com.agent.coding.backup;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipOutputStream;

/**
 * Backup creation with SSE progress events.
 * backup/_ops/create.py + create_helpers.py.
 *
 * Event shapes (serialized as {@code data: <json>\n\n}):
 *   {"type":"start","total_agents":N,"percent":0}
 *   {"type":"agent","agent_id":...,"index":i,"total":N,"percent":p}
 *   {"type":"saving","percent":90}
 *   {"type":"done","meta":{...},"percent":100}
 *   {"type":"error","message":...}
 */
public class BackupCreator {

    private static final Logger log = LoggerFactory.getLogger(BackupCreator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC);

    private BackupCreator() {
    }

    public interface EventSink {
        void emit(Map<String, Object> event) throws Exception;
    }

    /** Optional cooperative-cancellation probe checked between agents. */
    public interface CancelProbe {
        boolean isCancelRequested();
    }

    public static void create(BackupMeta.Scope scope,
                              List<String> agentIds,
                              String name,
                              String description,
                              EventSink sink) throws Exception {
        create(scope, agentIds, name, description, sink, null);
    }

    public static void create(BackupMeta.Scope scope,
                              List<String> agentIds,
                              String name,
                              String description,
                              EventSink sink,
                              CancelProbe cancelProbe) throws Exception {
        BackupMeta meta = new BackupMeta(
            BackupStore.generateBackupId(),
            name,
            description == null ? "" : description,
            TS.format(Instant.now()),
            scope,
            0);
        Path dir = BackupStore.backupDir();
        Files.createDirectories(dir);
        Path dest = BackupStore.zipPath(meta.id);
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);

        List<Map<String, Object>> validAgents = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (scope.include_agents) {
            for (String aid : agentIds) {
                Map<String, Object> profile = AgentStore.getProfile(aid);
                if (profile != null && !profile.isEmpty()) {
                    validAgents.add(profile);
                } else {
                    missing.add(aid);
                }
            }
        }
        Map<String, Object> startEvent = new LinkedHashMap<>();
        startEvent.put("type", "start");
        startEvent.put("total_agents", validAgents.size());
        startEvent.put("percent", 0);
        sink.emit(startEvent);
        if (!missing.isEmpty()) {
            log.warn("Skipping agents not found in config: {}", missing);
        }

        boolean cancelled = false;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            int backedUp = 0;
            if (scope.include_agents) {
                for (int i = 0; i < validAgents.size(); i++) {
                    if (cancelProbe != null && cancelProbe.isCancelRequested()) {
                        cancelled = true;
                        break;
                    }
                    Map<String, Object> profile = validAgents.get(i);
                    String aid = String.valueOf(profile.get("id"));
                    String workspaceDir = String.valueOf(profile.get("workspace_dir"));
                    Path ws = Path.of(workspaceDir);
                    if (Files.isDirectory(ws)) {
                        int total = validAgents.size();
                        int current = i + 1;
                        BackupStore.zipDirectory(zos, ws, ws, BackupStore.PREFIX_WORKSPACES + aid + "/", null);
                        int percent = (int) (10 + 75.0 * current / Math.max(total, 1));
                        Map<String, Object> agentEvent = new LinkedHashMap<>();
                        agentEvent.put("type", "agent");
                        agentEvent.put("agent_id", aid);
                        agentEvent.put("index", current);
                        agentEvent.put("total", total);
                        agentEvent.put("percent", percent);
                        sink.emit(agentEvent);
                        backedUp++;
                    }
                }
            }

            if (scope.include_global_config) {
                Path agentsJson = AgentStore.AGENTS_FILE;
                if (Files.isRegularFile(agentsJson)) {
                    zos.putNextEntry(new java.util.zip.ZipEntry(BackupStore.PREFIX_CONFIG));
                    Files.copy(agentsJson, zos);
                    zos.closeEntry();
                }
            }
            if (scope.include_skill_pool) {
                Path pool = SkillStore.getSkillPoolDir();
                if (Files.isDirectory(pool)) {
                    BackupStore.zipDirectory(zos, pool, pool, BackupStore.PREFIX_SKILL_POOL, null);
                }
            }
            if (scope.include_secrets) {
                Path secrets = secretsDir();
                if (Files.isDirectory(secrets)) {
                    BackupStore.zipDirectory(zos, secrets, secrets, BackupStore.PREFIX_SECRETS, null);
                }
            }

            if (!cancelled) {
                meta.agentCount = backedUp;
                meta.majoVersion = "majo";
                meta.systemInfo = systemInfo();
                meta.acceptedViaTrust = false;
                meta.signature = null;
                Map<String, Object> savingEvent = new LinkedHashMap<>();
                savingEvent.put("type", "saving");
                savingEvent.put("percent", 90);
                sink.emit(savingEvent);
                zos.putNextEntry(new java.util.zip.ZipEntry(BackupStore.META_FILE));
                zos.write(MAPPER.writeValueAsBytes(meta));
                zos.closeEntry();
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        if (cancelled) {
            Files.deleteIfExists(tmp);
            throw new BackupJobManager.BackupCancelledException();
        }

        // Sign with local key and atomically move into place.
        BackupMeta signed = meta;
        try {
            signed = signAndFinalize(tmp, meta, dest);
        } finally {
            Files.deleteIfExists(tmp);
        }
        Map<String, Object> doneEvent = new LinkedHashMap<>();
        doneEvent.put("type", "done");
        doneEvent.put("meta", signed);
        doneEvent.put("percent", 100);
        log.info("signAndFinalize: emitting done event for {}", dest.getFileName());
        sink.emit(doneEvent);
        log.info("signAndFinalize: done event emitted");
    }

    private static BackupMeta signAndFinalize(Path tmp, BackupMeta meta, Path dest) throws IOException {
        BackupMeta toSign = new BackupMeta(meta.id, meta.name, meta.description, meta.createdAt,
            meta.scope, meta.agentCount);
        toSign.majoVersion = meta.majoVersion;
        toSign.systemInfo = meta.systemInfo;
        toSign.acceptedViaTrust = false;
        toSign.signature = null;

        // Signature covers canonical meta (signature=null) + all zip entries
        // except meta.json, so it can be computed directly on tmp and needs
        // only one zip rewrite to embed the signed meta.json.
        String sig = BackupStore.computeSignature(tmp, toSign);
        toSign.signature = sig;
        log.info("signAndFinalize: signature={}", sig);

        Path finalTmp = dest.resolveSibling(dest.getFileName() + ".sig.tmp");
        Files.deleteIfExists(finalTmp);
        rewriteMeta(tmp, finalTmp, toSign);

        Files.deleteIfExists(tmp);
        Files.createDirectories(dest.getParent());
        Files.move(finalTmp, dest, StandardCopyOption.REPLACE_EXISTING);
        log.info("signAndFinalize: moved to {}", dest.getFileName());
        return toSign;
    }

    private static void rewriteMeta(Path src, Path dest, BackupMeta meta) throws IOException {
        try (java.util.zip.ZipFile srcZip = new java.util.zip.ZipFile(src.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dest))) {
            int copied = 0;
            out.putNextEntry(new java.util.zip.ZipEntry(BackupStore.META_FILE));
            out.write(MAPPER.writeValueAsBytes(meta));
            out.closeEntry();

            var entries = srcZip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (BackupStore.META_FILE.equals(entry.getName())) {
                    continue;
                }
                if (entry.isDirectory()) {
                    out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                    out.closeEntry();
                    continue;
                }
                if (copied % 500 == 0) {
                    log.info("rewriteMeta progress: copied {} (at {})", copied, entry.getName());
                }
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                try (var in = srcZip.getInputStream(entry)) {
                    in.transferTo(out);
                }
                out.closeEntry();
                copied++;
            }
            log.info("rewriteMeta: copied {} entries from {} to {}", copied, src.getFileName(), dest.getFileName());
        }
    }

    private static Map<String, Object> systemInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("os", System.getProperty("os.name"));
        info.put("os_version", System.getProperty("os.version"));
        info.put("os_release", System.getProperty("os.version"));
        info.put("machine", System.getProperty("os.arch"));
        info.put("java_version", System.getProperty("java.version"));
        return info;
    }

    public static Path secretsDir() {
        return SkillStore.WORKING_DIR.resolve(".secret");
    }
}
