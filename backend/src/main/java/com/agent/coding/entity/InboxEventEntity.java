package com.agent.coding.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "inbox_events")
public class InboxEventEntity {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "agent_id", length = 64, nullable = false)
    private String agentId = "default";

    @Column(name = "source_type", length = 32, nullable = false)
    private String sourceType;

    @Column(name = "source_id", length = 64, nullable = false)
    private String sourceId = "";

    @Column(name = "event_type", length = 32, nullable = false)
    private String eventType;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(length = 16, nullable = false)
    private String severity = "info";

    @Column(length = 255, nullable = false)
    private String title;

    @Column(columnDefinition = "CLOB", nullable = false)
    private String body;

    @Column(columnDefinition = "CLOB")
    private String payload;

    @Column(nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false)
    private double createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public InboxEventEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
    public double getCreatedAt() { return createdAt; }
    public void setCreatedAt(double createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
