package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AgentStatsResponse {
    @JsonProperty("agent_count") private int agentCount;
    @JsonProperty("active_count") private int activeCount;

    public AgentStatsResponse() {}
    public AgentStatsResponse(int agentCount, int activeCount) { this.agentCount = agentCount; this.activeCount = activeCount; }
    public int getAgentCount() { return agentCount; }
    public void setAgentCount(int agentCount) { this.agentCount = agentCount; }
    public int getActiveCount() { return activeCount; }
    public void setActiveCount(int activeCount) { this.activeCount = activeCount; }
}
