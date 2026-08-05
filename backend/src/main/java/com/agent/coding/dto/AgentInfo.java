package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Agent summary returned by GET /api/agents, aligned with the frontend
 * AgentSummary contract (frontend/src/api/types/agents.ts).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentInfo {
    @JsonProperty("id") private String id;
    @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;
    @JsonProperty("workspace_dir") private String workspaceDir;
    @JsonProperty("enabled") private boolean enabled;
    @JsonProperty("pinned") private boolean pinned;
    @JsonProperty("startup_status") private String startupStatus;
    @JsonProperty("backend") private String backend;
    @JsonProperty("backend_capabilities") private Object backendCapabilities;
    @JsonProperty("backend_model") private String backendModel;
    @JsonProperty("backend_reasoning_effort") private String backendReasoningEffort;
    @JsonProperty("active_model") private Object activeModel;

    public AgentInfo() {}
    public AgentInfo(String id, String name, String description, String workspaceDir, boolean enabled, String startupStatus, String backend, Object backendCapabilities) {
        this.id = id; this.name = name; this.description = description;
        this.workspaceDir = workspaceDir; this.enabled = enabled;
        this.startupStatus = startupStatus; this.backend = backend;
        this.backendCapabilities = backendCapabilities;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public String getStartupStatus() { return startupStatus; }
    public void setStartupStatus(String startupStatus) { this.startupStatus = startupStatus; }
    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public Object getBackendCapabilities() { return backendCapabilities; }
    public void setBackendCapabilities(Object backendCapabilities) { this.backendCapabilities = backendCapabilities; }
    public String getBackendModel() { return backendModel; }
    public void setBackendModel(String backendModel) { this.backendModel = backendModel; }
    public String getBackendReasoningEffort() { return backendReasoningEffort; }
    public void setBackendReasoningEffort(String backendReasoningEffort) { this.backendReasoningEffort = backendReasoningEffort; }
    public Object getActiveModel() { return activeModel; }
    public void setActiveModel(Object activeModel) { this.activeModel = activeModel; }
}
