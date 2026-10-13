package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TokenUsageStats {
    @JsonProperty("provider_id")
    private String providerId;
    @JsonProperty("model")
    private String model;
    @JsonProperty("prompt_tokens")
    private long promptTokens;
    @JsonProperty("completion_tokens")
    private long completionTokens;
    @JsonProperty("call_count")
    private long callCount;
    /** Accumulated cost; null when no record in scope had a known price (ADR-0017). */
    @JsonProperty("cost_usd")
    private Double costUsd;

    public TokenUsageStats() {}
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }
    public long getCallCount() { return callCount; }
    public void setCallCount(long callCount) { this.callCount = callCount; }
    public Double getCostUsd() { return costUsd; }
    public void setCostUsd(Double costUsd) { this.costUsd = costUsd; }

    /** Null-safe accumulate: unknown stays null until a priced record lands. */
    public void addCost(Double cost) {
        if (cost != null) {
            costUsd = (costUsd == null ? 0d : costUsd) + cost;
        }
    }
}
