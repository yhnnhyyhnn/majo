package com.agent.coding.inbox;

/**
 * Value holder for a single inbox event, mirroring the JSON shape written by
 * qwenpaw's {@code inbox_store.append_event}. Field names are serialized
 * verbatim (camelCase = snake_case) so the console UI contract is preserved.
 */
public record InboxEvent(
        String id,
        String agentId,
        String sourceType,
        String sourceId,
        String eventType,
        String status,
        String severity,
        String title,
        String body,
        com.fasterxml.jackson.databind.JsonNode payload,
        boolean read,
        double createdAt) {

    public java.util.Map<String, Object> toMap() {
        return java.util.Map.ofEntries(
                java.util.Map.entry("id", id),
                java.util.Map.entry("agent_id", agentId),
                java.util.Map.entry("source_type", sourceType),
                java.util.Map.entry("source_id", sourceId),
                java.util.Map.entry("event_type", eventType),
                java.util.Map.entry("status", status),
                java.util.Map.entry("severity", severity),
                java.util.Map.entry("title", title),
                java.util.Map.entry("body", body()),
                java.util.Map.entry("payload", payload),
                java.util.Map.entry("read", read),
                java.util.Map.entry("created_at", createdAt));
    }
}