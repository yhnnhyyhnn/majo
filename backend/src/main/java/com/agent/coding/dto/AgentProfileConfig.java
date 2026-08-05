package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Complete agent profile config, matching the frontend AgentProfileConfig
 * contract (frontend/src/api/types/agents.ts).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentProfileConfig {
    @JsonProperty("id") private String id;
    @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;
    @JsonProperty("workspace_dir") private String workspaceDir;
    @JsonProperty("backend") private String backend;
    @JsonProperty("backend_settings") private Object backendSettings;
    @JsonProperty("language") private String language;
    @JsonProperty("active_model") private Object activeModel;

    public AgentProfileConfig() {}

    public AgentProfileConfig(String id, String name, String description, String workspaceDir,
                              String backend, Object backendSettings, String language, Object activeModel) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.workspaceDir = workspaceDir;
        this.backend = backend;
        this.backendSettings = backendSettings;
        this.language = language;
        this.activeModel = activeModel;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public Object getBackendSettings() { return backendSettings; }
    public void setBackendSettings(Object backendSettings) { this.backendSettings = backendSettings; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Object getActiveModel() { return activeModel; }
    public void setActiveModel(Object activeModel) { this.activeModel = activeModel; }
}
