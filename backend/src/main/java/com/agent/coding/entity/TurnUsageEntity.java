package com.agent.coding.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One completed agent turn's token usage (ADR-0011). */
@Entity
@Table(name = "token_usage_turns")
public class TurnUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", length = 64, nullable = false)
    private String agentId = "default";

    @Column(name = "chat_id", length = 64)
    private String chatId;

    @Column(name = "provider_id", length = 64, nullable = false)
    private String providerId = "";

    @Column(length = 128, nullable = false)
    private String model = "";

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public TurnUsageEntity() {}

    public TurnUsageEntity(String agentId, String chatId, String providerId, String model,
                           int inputTokens, int outputTokens, long durationMs) {
        this.agentId = agentId;
        this.chatId = chatId;
        this.providerId = providerId;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.durationMs = durationMs;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
