package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatSpecDto {
    @JsonProperty("id") private String id;
    @JsonProperty("session_id") private String sessionId;
    @JsonProperty("user_id") private String userId;
    @JsonProperty("channel") private String channel;
    @JsonProperty("name") private String name;
    @JsonProperty("created_at") private String createdAt;
    @JsonProperty("updated_at") private String updatedAt;
    @JsonProperty("meta") private Object meta;
    @JsonProperty("status") private String status;
    @JsonProperty("pinned") private Boolean pinned;
    @JsonProperty("archived_at") private String archivedAt;
    @JsonProperty("archived") private Boolean archived;

    public static ChatSpecDto from(com.agent.coding.entity.ChatEntity c) {
        var d = new ChatSpecDto();
        d.id = c.getId();
        d.sessionId = c.getSessionId().isEmpty() ? ("console:" + c.getId()) : c.getSessionId();
        d.userId = c.getUserId();
        d.channel = c.getChannel();
        d.name = c.getTitle();
        d.status = c.getStatus();
        d.createdAt = c.getCreatedAt() != null ? c.getCreatedAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
        d.updatedAt = c.getUpdatedAt() != null ? c.getUpdatedAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
        d.pinned = c.getPinned();
        d.archived = c.getArchivedAt() != null;
        d.archivedAt = c.getArchivedAt() != null ? c.getArchivedAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
        d.meta = new java.util.LinkedHashMap<>();
        return d;
    }

    // Getters only (read-only DTO)
    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public String getChannel() { return channel; }
    public String getName() { return name; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public Object getMeta() { return meta; }
    public String getStatus() { return status; }
    public Boolean getPinned() { return pinned; }
    public String getArchivedAt() { return archivedAt; }
    public Boolean getArchived() { return archived; }
}
