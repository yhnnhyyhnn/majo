package com.agent.coding.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "token_usage")
public class TokenUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usage_date", length = 10, nullable = false)
    private String usageDate;

    @Column(name = "provider_id", length = 64, nullable = false)
    private String providerId = "";

    @Column(length = 128, nullable = false)
    private String model = "";

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "call_count", nullable = false)
    private int callCount = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public TokenUsageEntity() {}

    public TokenUsageEntity(String usageDate, String providerId, String model,
                             int promptTokens, int completionTokens) {
        this.usageDate = usageDate;
        this.providerId = providerId;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsageDate() { return usageDate; }
    public void setUsageDate(String usageDate) { this.usageDate = usageDate; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
    public int getCallCount() { return callCount; }
    public void setCallCount(int callCount) { this.callCount = callCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
