package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Request body for PUT /api/agents/order (persist the agent display order). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReorderAgentsRequest {
    @JsonProperty("agent_ids") private List<String> agentIds;

    public List<String> getAgentIds() { return agentIds; }
    public void setAgentIds(List<String> agentIds) { this.agentIds = agentIds; }
}
