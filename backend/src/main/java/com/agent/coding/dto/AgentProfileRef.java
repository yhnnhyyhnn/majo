package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reference returned by agent create/copy operations, matching the frontend
 * AgentProfileRef contract (frontend/src/api/types/agents.ts).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentProfileRef {
    @JsonProperty("id") private String id;
    @JsonProperty("workspace_dir") private String workspaceDir;
    @JsonProperty("enabled") private Boolean enabled;
    @JsonProperty("pinned") private Boolean pinned;

    public AgentProfileRef() {}

    public AgentProfileRef(String id, String workspaceDir, Boolean enabled, Boolean pinned) {
        this.id = id;
        this.workspaceDir = workspaceDir;
        this.enabled = enabled;
        this.pinned = pinned;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getPinned() { return pinned; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }
}
