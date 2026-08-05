package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request body for POST /api/agents, matching the frontend CreateAgentRequest
 * (frontend/src/api/types/agents.ts).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateAgentRequest {
    @JsonProperty("id") private String id;
    @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;
    @JsonProperty("workspace_dir") private String workspaceDir;
    @JsonProperty("language") private String language;
    @JsonProperty("skill_names") private List<String> skillNames;
    @JsonProperty("active_model") private Object activeModel;
    @JsonProperty("backend") private String backend;
    @JsonProperty("backend_settings") private Object backendSettings;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public List<String> getSkillNames() { return skillNames; }
    public void setSkillNames(List<String> skillNames) { this.skillNames = skillNames; }
    public Object getActiveModel() { return activeModel; }
    public void setActiveModel(Object activeModel) { this.activeModel = activeModel; }
    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public Object getBackendSettings() { return backendSettings; }
    public void setBackendSettings(Object backendSettings) { this.backendSettings = backendSettings; }
}
