package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Response for GET /api/agents, matching the frontend AgentListResponse. */
public class AgentListResponse {
    @JsonProperty("agents") private List<AgentInfo> agents;

    public AgentListResponse() {}

    public AgentListResponse(List<AgentInfo> agents) {
        this.agents = agents;
    }

    public List<AgentInfo> getAgents() { return agents; }
    public void setAgents(List<AgentInfo> agents) { this.agents = agents; }
}
