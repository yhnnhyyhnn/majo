package com.agent.coding.skill;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data models for the skills API. Mirrors frontend/src/api/types/skill.ts
 * and the Python skill_system manifest shapes.
 */
public final class SkillModels {

    private SkillModels() {}

    // ------------------------------------------------------------------
    // Enums
    // ------------------------------------------------------------------

    /** Sync state of a pool skill against its workspace copies. */
    public enum SkillSyncStatus {
        SYNCED("synced"),
        OUTDATED("outdated"),
        NOT_SYNCED("not_synced"),
        CONFLICT("conflict"),
        DASH("-");

        private final String value;
        SkillSyncStatus(String value) { this.value = value; }
        public String value() { return value; }
        @Override public String toString() { return value; }
    }

    /** Hub install task lifecycle states. */
    public enum HubTaskStatus {
        PENDING("pending"),
        IMPORTING("importing"),
        COMPLETED("completed"),
        FAILED("failed"),
        CANCELLED("cancelled");

        private final String value;
        HubTaskStatus(String value) { this.value = value; }
        public String value() { return value; }
        @Override public String toString() { return value; }
    }

    // ------------------------------------------------------------------
    // Workspace skill spec (types/skill.ts SkillSpec)
    // ------------------------------------------------------------------

    @JsonInclude(Include.NON_NULL)
    public static class SkillSpec {
        @JsonProperty("name") public String name;
        @JsonProperty("description") public String description;
        @JsonProperty("version_text") public String versionText;
        @JsonProperty("content") public String content;
        @JsonProperty("source") public String source;
        @JsonProperty("enabled") public Boolean enabled;
        @JsonProperty("channels") public List<String> channels;
        @JsonProperty("tags") public List<String> tags;
        @JsonProperty("config") public Map<String, Object> config;
        @JsonProperty("last_updated") public String lastUpdated;
        @JsonProperty("emoji") public String emoji;
        @JsonProperty("installed_from") public String installedFrom;
    }

    // ------------------------------------------------------------------
    // Pool skill spec (types/skill.ts PoolSkillSpec)
    // ------------------------------------------------------------------

    @JsonInclude(Include.NON_NULL)
    public static class PoolSkillSpec {
        @JsonProperty("name") public String name;
        @JsonProperty("description") public String description;
        @JsonProperty("version_text") public String versionText;
        @JsonProperty("content") public String content;
        @JsonProperty("source") public String source;
        @JsonProperty("protected") public boolean protectedFlag;
        @JsonProperty("external") public Boolean external;
        @JsonProperty("external_path") public String externalPath;
        @JsonProperty("commit_text") public String commitText;
        @JsonProperty("sync_status") public String syncStatus;
        @JsonProperty("latest_version_text") public String latestVersionText;
        @JsonProperty("builtin_language") public String builtinLanguage;
        @JsonProperty("available_builtin_languages") public List<String> availableBuiltinLanguages;
        @JsonProperty("tags") public List<String> tags;
        @JsonProperty("config") public Map<String, Object> config;
        @JsonProperty("last_updated") public String lastUpdated;
        @JsonProperty("emoji") public String emoji;
        @JsonProperty("installed_from") public String installedFrom;
        @JsonProperty("auto_update") public Boolean autoUpdate;
        @JsonProperty("auto_update_targets") public List<String> autoUpdateTargets;
    }

    // ------------------------------------------------------------------
    // Workspace summary (types/skill.ts WorkspaceSkillSummary)
    // ------------------------------------------------------------------

    @JsonInclude(Include.NON_NULL)
    public static class WorkspaceSkillSummary {
        @JsonProperty("agent_id") public String agentId;
        @JsonProperty("agent_name") public String agentName;
        @JsonProperty("workspace_dir") public String workspaceDir;
        @JsonProperty("skills") public List<SkillSpec> skills;
    }

    // ------------------------------------------------------------------
    // Builtin import spec (types/skill.ts BuiltinImportSpec / BuiltinLanguageSpec)
    // ------------------------------------------------------------------

    @JsonInclude(Include.NON_NULL)
    public static class BuiltinLanguageSpec {
        @JsonProperty("language") public String language;
        @JsonProperty("description") public String description;
        @JsonProperty("version_text") public String versionText;
        @JsonProperty("source_name") public String sourceName;
        @JsonProperty("status") public String status;
    }

    @JsonInclude(Include.NON_NULL)
    public static class BuiltinImportSpec {
        @JsonProperty("name") public String name;
        @JsonProperty("description") public String description;
        @JsonProperty("version_text") public String versionText;
        @JsonProperty("current_version_text") public String currentVersionText;
        @JsonProperty("current_source") public String currentSource;
        @JsonProperty("current_language") public String currentLanguage;
        @JsonProperty("available_languages") public List<String> availableLanguages;
        @JsonProperty("languages") public Map<String, BuiltinLanguageSpec> languages;
        @JsonProperty("status") public String status;
    }

    @JsonInclude(Include.NON_NULL)
    public static class BuiltinRemovedSpec {
        @JsonProperty("name") public String name;
        @JsonProperty("description") public String description;
        @JsonProperty("current_version_text") public String currentVersionText;
        @JsonProperty("current_source") public String currentSource;
    }

    @JsonInclude(Include.NON_NULL)
    public static class BuiltinUpdateNotice {
        @JsonProperty("fingerprint") public String fingerprint;
        @JsonProperty("has_updates") public boolean hasUpdates;
        @JsonProperty("total_changes") public int totalChanges;
        @JsonProperty("actionable_skill_names") public List<String> actionableSkillNames = new ArrayList<>();
        @JsonProperty("added") public List<BuiltinImportSpec> added = new ArrayList<>();
        @JsonProperty("missing") public List<BuiltinImportSpec> missing = new ArrayList<>();
        @JsonProperty("updated") public List<BuiltinImportSpec> updated = new ArrayList<>();
        @JsonProperty("removed") public List<BuiltinRemovedSpec> removed = new ArrayList<>();
    }

    // ------------------------------------------------------------------
    // Hub (types/skill.ts HubSkillSpec / HubInstallTaskResponse)
    // ------------------------------------------------------------------

    @JsonInclude(Include.NON_NULL)
    public static class HubSkillSpec {
        @JsonProperty("slug") public String slug;
        @JsonProperty("name") public String name;
        @JsonProperty("description") public String description;
        @JsonProperty("version") public String version;
        @JsonProperty("source_url") public String sourceUrl;
        @JsonProperty("author") public String author;
        @JsonProperty("icon_url") public String iconUrl;
    }

    @JsonInclude(Include.NON_NULL)
    public static class HubInstallTask {
        @JsonProperty("task_id") public String taskId;
        @JsonProperty("bundle_url") public String bundleUrl;
        @JsonProperty("version") public String version;
        @JsonProperty("enable") public boolean enable;
        @JsonProperty("status") public String status;
        @JsonProperty("error") public String error;
        @JsonProperty("result") public Map<String, Object> result;
        @JsonProperty("created_at") public long createdAt;
        @JsonProperty("updated_at") public long updatedAt;

        public HubInstallTask() {}
        public HubInstallTask(String taskId, String bundleUrl, String version,
                              boolean enable, String status) {
            this.taskId = taskId;
            this.bundleUrl = bundleUrl;
            this.version = version == null ? "" : version;
            this.enable = enable;
            this.status = status;
            this.error = null;
            this.result = null;
            long now = System.currentTimeMillis();
            this.createdAt = now;
            this.updatedAt = now;
        }
    }

    // ------------------------------------------------------------------
    // Manifest containers
    // ------------------------------------------------------------------

    /** Workspace manifest: <workspace>/skill.json */
    public static class WorkspaceManifest {
        public String schemaVersion = "workspace-skill-manifest.v1";
        public int version = 0;
        public Map<String, Map<String, Object>> skills = new HashMap<>();

        public static WorkspaceManifest from(Map<String, Object> raw) {
            WorkspaceManifest m = new WorkspaceManifest();
            if (raw == null) return m;
            Object sv = raw.get("schema_version");
            if (sv instanceof String s) m.schemaVersion = s;
            Object v = raw.get("version");
            if (v instanceof Number n) m.version = n.intValue();
            Object sk = raw.get("skills");
            if (sk instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() instanceof String k && e.getValue() instanceof Map<?, ?> mv) {
                        Map<String, Object> entry = new HashMap<>();
                        for (Map.Entry<?, ?> f : mv.entrySet()) {
                            if (f.getKey() instanceof String fk) entry.put(fk, f.getValue());
                        }
                        m.skills.put(k, entry);
                    }
                }
            }
            return m;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("schema_version", schemaVersion);
            map.put("version", version);
            map.put("skills", skills);
            return map;
        }
    }

    /** Pool manifest: <pool>/skill.json */
    public static class PoolManifest {
        public String schemaVersion = "skill-pool-manifest.v1";
        public int version = 0;
        public Map<String, Map<String, Object>> skills = new HashMap<>();
        public List<String> builtinSkillNames = new ArrayList<>();

        public static PoolManifest from(Map<String, Object> raw) {
            PoolManifest m = new PoolManifest();
            if (raw == null) return m;
            Object sv = raw.get("schema_version");
            if (sv instanceof String s) m.schemaVersion = s;
            Object v = raw.get("version");
            if (v instanceof Number n) m.version = n.intValue();
            Object sk = raw.get("skills");
            if (sk instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() instanceof String k && e.getValue() instanceof Map<?, ?> mv) {
                        Map<String, Object> entry = new HashMap<>();
                        for (Map.Entry<?, ?> f : mv.entrySet()) {
                            if (f.getKey() instanceof String fk) entry.put(fk, f.getValue());
                        }
                        m.skills.put(k, entry);
                    }
                }
            }
            Object bn = raw.get("builtin_skill_names");
            if (bn instanceof List<?> list) {
                for (Object o : list) if (o instanceof String s) m.builtinSkillNames.add(s);
            }
            return m;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("schema_version", schemaVersion);
            map.put("version", version);
            map.put("skills", skills);
            map.put("builtin_skill_names", builtinSkillNames);
            return map;
        }
    }
}
