package com.agent.coding.backup;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillStore;
import com.agent.coding.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Backup restore.. Restores agent
 * workspaces, global config (agents.json), skill pool and secrets with
 * full/custom mode and trust semantics.
 */
public class BackupRestorer {

    private static final Logger log = LoggerFactory.getLogger(BackupRestorer.class);

    private BackupRestorer() {
    }

    public static BackupMeta preflight(String backupId, RestoreRequest req) {
        Path zip = BackupStore.findZipPath(backupId);
        if (zip == null) {
            throw new IllegalArgumentException("Backup not found: " + backupId);
        }
        BackupMeta meta = BackupStore.readMetaFromZip(zip);
        if (meta == null) {
            throw new IllegalArgumentException("Backup not found: " + backupId);
        }
        if (meta.signature != null && !meta.signature.isBlank()
                && !BackupStore.verifySignature(zip, meta) && !"foreign".equals(req.trust_mode)) {
            throw new IllegalArgumentException("Backup signature is invalid or untrusted");
        }
        if (meta.signature == null && !"legacy".equals(req.trust_mode)) {
            throw new IllegalArgumentException("Backup signature is invalid or untrusted");
        }
        if (meta.version != null && !"1".equals(meta.version)) {
            throw new IllegalArgumentException("Unsupported backup version: " + meta.version);
        }
        return meta;
    }

    public static class RestoreRequest {
        public boolean include_agents = true;
        public List<String> agent_ids = new ArrayList<>();
        public boolean include_global_config = true;
        public boolean include_secrets = false;
        public boolean include_skill_pool = true;
        public String default_workspace_dir;
        public String mode = "custom";
        public Boolean preserve_local_protected_config;
        public String trust_mode;
    }

    public static RestoreResponse restore(String backupId, RestoreRequest req) {
        Path zip = BackupStore.findZipPath(backupId);
        if (zip == null) {
            throw new IllegalArgumentException("Backup not found: " + backupId);
        }
        preflight(backupId, req);
        BackupMeta meta = BackupStore.readMetaFromZip(zip);

        List<String> restoredKeys = new ArrayList<>();

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            if (req.include_agents) {
                restoreAgents(zf, zip, req, meta);
            }
            if (req.include_skill_pool) {
                restoreSkillPool(zf, zip);
            }
            if (req.include_secrets) {
                restoreSecrets(zf, zip);
            }
            if (req.include_global_config) {
                restoredKeys.addAll(restoreGlobalConfig(zf, zip, req, meta));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Restore failed: " + e.getMessage(), e);
        }

        RestoreResponse resp = new RestoreResponse();
        resp.ok = true;
        resp.preserved_local_keys = restoredKeys;
        return resp;
    }

    private static void restoreAgents(ZipFile zf, Path zip, RestoreRequest req, BackupMeta meta)
        throws IOException {
        List<String> agentIds = req.agent_ids.isEmpty()
            ? BackupStore.zipAgentIds(zip)
            : req.agent_ids;
        for (String aid : agentIds) {
            String prefix = BackupStore.PREFIX_WORKSPACES + aid + "/";
            Map<String, Object> profile = AgentStore.getProfile(aid);
            Path dest;
            if (profile != null && profile.get("workspace_dir") != null) {
                dest = Path.of(String.valueOf(profile.get("workspace_dir")));
            } else {
                String base = (req.default_workspace_dir != null && !req.default_workspace_dir.isBlank())
                    ? req.default_workspace_dir : String.valueOf(SkillStore.WORKING_DIR.resolve("workspaces"));
                dest = Path.of(base).resolve(aid);
            }
            extractWorkspace(zf, prefix, dest);
            log.info("Restored agent workspace: {} -> {}", aid, dest);
        }
    }

    private static void extractWorkspace(ZipFile zf, String prefix, Path dest) throws IOException {
        Files.createDirectories(dest);
        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String rel = name.substring(prefix.length());
            Path target = dest.resolve(rel).normalize();
            if (!target.startsWith(dest.toAbsolutePath().normalize())) {
                continue;
            }
            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                try (var in = zf.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void restoreSkillPool(ZipFile zf, Path zip) throws IOException {
        Path dest = SkillStore.getSkillPoolDir();
        Files.createDirectories(dest);
        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(BackupStore.PREFIX_SKILL_POOL)) {
                continue;
            }
            String rel = name.substring(BackupStore.PREFIX_SKILL_POOL.length());
            Path target = dest.resolve(rel).normalize();
            if (!target.startsWith(dest.toAbsolutePath().normalize())) {
                continue;
            }
            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                try (var in = zf.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void restoreSecrets(ZipFile zf, Path zip) throws IOException {
        Path dest = BackupCreator.secretsDir();
        Files.createDirectories(dest);
        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(BackupStore.PREFIX_SECRETS)) {
                continue;
            }
            String rel = name.substring(BackupStore.PREFIX_SECRETS.length());
            Path target = dest.resolve(rel).normalize();
            if (!target.startsWith(dest.toAbsolutePath().normalize())) {
                continue;
            }
            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                try (var in = zf.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static List<String> restoreGlobalConfig(ZipFile zf, Path zip, RestoreRequest req, BackupMeta meta)
        throws IOException {
        List<String> preserved = new ArrayList<>();
        var entry = zf.getEntry(BackupStore.PREFIX_CONFIG);
        if (entry == null) {
            return preserved;
        }
        Map<String, Object> backupConfig;
        try {
            backupConfig = BackupStore.MAPPER.readValue(zf.getInputStream(entry), Map.class);
        } catch (Exception e) {
            throw new IOException("Failed to parse backup config.json", e);
        }
        Map<String, Object> currentConfig = AgentStore.loadConfig();

        Map<String, Object> merged = new LinkedHashMap<>(backupConfig);

        boolean preserve = req.preserve_local_protected_config != null
            ? req.preserve_local_protected_config
            : !isTrustedRestore(req);
        if (preserve) {
            // Keep local protected keys: active_model / model routing / api keys etc.
            for (String key : new String[]{"active_model", "providers", "settings", "llm_routing"}) {
                if (currentConfig.containsKey(key)) {
                    merged.put(key, currentConfig.get(key));
                    preserved.add(key);
                }
            }
        }

        if ("custom".equals(req.mode)) {
            // Rebuild agents.profiles from current registry, overwriting restored ones.
            Map<String, Object> localProfiles = new LinkedHashMap<>();
            for (Map<String, Object> profile : AgentStore.listProfiles()) {
                String id = SkillService.str(profile.get("id"));
                if (req.include_agents && req.agent_ids.contains(id)) {
                    continue; // will be overwritten from backup below
                }
                localProfiles.put(id, profile);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> backupProfiles = (Map<String, Object>) backupConfig.getOrDefault("profiles", Map.of());
            for (String aid : req.agent_ids) {
                Object bp = backupProfiles.get(aid);
                if (bp != null) {
                    localProfiles.put(aid, bp);
                }
            }
            merged.put("profiles", localProfiles);
        }

        writeAgentsJson(merged);
        return preserved;
    }

    private static boolean isTrustedRestore(RestoreRequest req) {
        return "legacy".equals(req.trust_mode) || "foreign".equals(req.trust_mode);
    }

    private static void writeAgentsJson(Map<String, Object> config) throws IOException {
        Files.createDirectories(AgentStore.AGENTS_FILE.getParent());
        BackupStore.MAPPER.writerWithDefaultPrettyPrinter()
            .writeValue(AgentStore.AGENTS_FILE.toFile(), config);
        log.info("Restored agents.json");
    }

    public static class RestoreResponse {
        public boolean ok;
        public List<String> preserved_local_keys = new ArrayList<>();
    }
}
