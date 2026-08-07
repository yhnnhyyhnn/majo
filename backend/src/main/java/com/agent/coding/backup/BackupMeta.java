package com.agent.coding.backup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Backup metadata, ported from qwenpaw backup/models.py. Mirrors the frontend
 * BackupMeta / BackupScope / BackupDetail contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BackupMeta {

    public static class Scope {
        public boolean include_agents = true;
        public boolean include_global_config = true;
        public boolean include_secrets = false;
        public boolean include_skill_pool = true;

        public Scope() {
        }

        public Scope(boolean includeAgents, boolean includeGlobalConfig,
                     boolean includeSecrets, boolean includeSkillPool) {
            this.include_agents = includeAgents;
            this.include_global_config = includeGlobalConfig;
            this.include_secrets = includeSecrets;
            this.include_skill_pool = includeSkillPool;
        }
    }

    @JsonProperty("id") public String id;
    @JsonProperty("name") public String name;
    @JsonProperty("description") public String description = "";
    @JsonProperty("created_at") public String createdAt;
    @JsonProperty("version") public String version = "1";
    @JsonProperty("scope") public Scope scope = new Scope();
    @JsonProperty("agent_count") public int agentCount;
    @JsonProperty("qwenpaw_version") public String qwenpawVersion = "";
    @JsonProperty("system_info") public Map<String, Object> systemInfo = new LinkedHashMap<>();
    @JsonProperty("signature") public String signature;
    @JsonProperty("accepted_via_trust") public Boolean acceptedViaTrust;
    @JsonProperty("workspace_stats") public Map<String, Object> workspaceStats;

    public BackupMeta() {
    }

    public BackupMeta(String id, String name, String description, String createdAt,
                      Scope scope, int agentCount) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.scope = scope;
        this.agentCount = agentCount;
    }
}
