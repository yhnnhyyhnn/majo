package com.agent.coding.inbox;

import com.agent.coding.entity.InboxEventEntity;
import com.agent.coding.repository.InboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
public class InboxStore {

    private static final int MAX_EVENTS = 5000;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final InboxEventRepository repo;

    public InboxStore(InboxEventRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Map<String, Object> appendEvent(
            String agentId, String sourceType, String sourceId,
            String eventType, String status, String title, String body,
            String severity, Map<String, Object> payload) {
        var e = new InboxEventEntity();
        e.setId(UUID.randomUUID().toString());
        e.setAgentId((agentId == null || agentId.isBlank()) ? "default" : agentId);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId == null ? "" : sourceId);
        e.setEventType(eventType);
        e.setStatus(status);
        e.setSeverity((severity == null || severity.isBlank()) ? "info" : severity);
        e.setTitle(title);
        e.setBody(body);
        try {
            e.setPayload(payload != null ? mapper.writeValueAsString(payload) : "{}");
        } catch (JsonProcessingException ex) {
            e.setPayload("{}");
        }
        e.setRead(false);
        e.setCreatedAt(System.currentTimeMillis() / 1000.0);
        repo.save(e);

        trimExcess();
        return toMap(e);
    }

    private void trimExcess() {
        List<InboxEventEntity> all = repo.findAllOrderByCreatedAtDesc();
        if (all.size() > MAX_EVENTS) {
            repo.deleteAll(all.subList(MAX_EVENTS, all.size()));
        }
    }

    public List<Map<String, Object>> listEvents(
            int limit, int offset, String sourceType, String status, String agentId, boolean unreadOnly) {
        var events = repo.findAllOrderByCreatedAtDesc().stream()
            .filter(e -> sourceType == null || sourceType.isBlank() || sourceType.equals(e.getSourceType()))
            .filter(e -> status == null || status.isBlank() || status.equals(e.getStatus()))
            .filter(e -> agentId == null || agentId.isBlank() || agentId.equals(e.getAgentId()))
            .filter(e -> !unreadOnly || !e.isRead())
            .toList();
        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.max(limit, 0);
        if (safeOffset >= events.size()) return List.of();
        int end = Math.min(events.size(), safeOffset + safeLimit);
        return events.subList(safeOffset, end).stream().map(InboxStore::toMap).toList();
    }

    @Transactional
    public int markRead(List<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) return 0;
        return repo.markRead(eventIds);
    }

    @Transactional
    public int markAllRead() {
        return repo.markAllRead();
    }

    @Transactional
    public DeletedEvent deleteEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) return new DeletedEvent(false, null, false);
        var opt = repo.findById(eventId);
        if (opt.isEmpty()) return new DeletedEvent(false, null, false);
        var e = opt.get();
        String payloadStr = e.getPayload();
        String deletedRunId = extractRunId(payloadStr);
        repo.deleteById(eventId);

        boolean stillRef = false;
        if (deletedRunId != null) {
            stillRef = repo.findAllOrderByCreatedAtDesc().stream().anyMatch(other -> {
                var p = parsePayload(other.getPayload());
                return deletedRunId.equals(p.get("run_id"));
            });
        }
        return new DeletedEvent(true, deletedRunId, stillRef);
    }

    private static String extractRunId(String payloadStr) {
        if (payloadStr == null || payloadStr.isBlank()) return null;
        try {
            var p = mapper.readValue(payloadStr, Map.class);
            Object rid = p.get("run_id");
            return rid instanceof String s ? s : null;
        } catch (Exception e) { return null; }
    }

    private static Map<String, Object> parsePayload(String payloadStr) {
        if (payloadStr == null || payloadStr.isBlank()) return Map.of();
        try { return mapper.readValue(payloadStr, Map.class); } catch (Exception e) { return Map.of(); }
    }

    static Map<String, Object> toMap(InboxEventEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("agent_id", e.getAgentId());
        m.put("source_type", e.getSourceType());
        m.put("source_id", e.getSourceId());
        m.put("event_type", e.getEventType());
        m.put("status", e.getStatus());
        m.put("severity", e.getSeverity());
        m.put("title", e.getTitle());
        m.put("body", e.getBody());
        m.put("payload", parsePayload(e.getPayload()));
        m.put("read", e.isRead());
        m.put("created_at", e.getCreatedAt());
        return m;
    }

    public record DeletedEvent(boolean deleted, String runId, boolean runIdStillReferenced) {}
}
