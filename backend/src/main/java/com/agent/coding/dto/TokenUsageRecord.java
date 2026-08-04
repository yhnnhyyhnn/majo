package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TokenUsageRecord {
    @JsonProperty("date")
    private String date;
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

    public TokenUsageRecord() {}

    public TokenUsageRecord(String date, String providerId, String model,
                             long promptTokens, long completionTokens, long callCount) {
        this.date = date; this.providerId = providerId; this.model = model;
        this.promptTokens = promptTokens; this.completionTokens = completionTokens;
        this.callCount = callCount;
    }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
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
}
