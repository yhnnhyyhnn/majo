package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /api/agents/{agentId}/copy, matching the frontend
 * CopyAgentRequest (frontend/src/api/types/agents.ts).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CopyAgentRequest {
    @JsonProperty("name") private String name;
    @JsonProperty("copy_agent_json") private Boolean copyAgentJson = true;
    @JsonProperty("copy_md_files") private Boolean copyMdFiles = true;
    @JsonProperty("copy_skills") private Boolean copySkills = false;
    @JsonProperty("copy_jobs") private Boolean copyJobs = false;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getCopyAgentJson() { return copyAgentJson; }
    public void setCopyAgentJson(Boolean copyAgentJson) { this.copyAgentJson = copyAgentJson; }
    public Boolean getCopyMdFiles() { return copyMdFiles; }
    public void setCopyMdFiles(Boolean copyMdFiles) { this.copyMdFiles = copyMdFiles; }
    public Boolean getCopySkills() { return copySkills; }
    public void setCopySkills(Boolean copySkills) { this.copySkills = copySkills; }
    public Boolean getCopyJobs() { return copyJobs; }
    public void setCopyJobs(Boolean copyJobs) { this.copyJobs = copyJobs; }
}
