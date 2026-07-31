package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class TokenUsageSummary {
    @JsonProperty("total_prompt_tokens")
    private long totalPromptTokens;
    @JsonProperty("total_completion_tokens")
    private long totalCompletionTokens;
    @JsonProperty("total_calls")
    private long totalCalls;
    @JsonProperty("by_model")
    private Map<String, TokenUsageStats> byModel;
    @JsonProperty("by_date")
    private Map<String, TokenUsageStats> byDate;

    public TokenUsageSummary() {}

    public TokenUsageSummary(long totalPromptTokens, long totalCompletionTokens, long totalCalls,
                              Map<String, TokenUsageStats> byModel, Map<String, TokenUsageStats> byDate) {
        this.totalPromptTokens = totalPromptTokens;
        this.totalCompletionTokens = totalCompletionTokens;
        this.totalCalls = totalCalls;
        this.byModel = byModel;
        this.byDate = byDate;
    }

    public long getTotalPromptTokens() { return totalPromptTokens; }
    public void setTotalPromptTokens(long totalPromptTokens) { this.totalPromptTokens = totalPromptTokens; }
    public long getTotalCompletionTokens() { return totalCompletionTokens; }
    public void setTotalCompletionTokens(long totalCompletionTokens) { this.totalCompletionTokens = totalCompletionTokens; }
    public long getTotalCalls() { return totalCalls; }
    public void setTotalCalls(long totalCalls) { this.totalCalls = totalCalls; }
    public Map<String, TokenUsageStats> getByModel() { return byModel; }
    public void setByModel(Map<String, TokenUsageStats> byModel) { this.byModel = byModel; }
    public Map<String, TokenUsageStats> getByDate() { return byDate; }
    public void setByDate(Map<String, TokenUsageStats> byDate) { this.byDate = byDate; }
}
