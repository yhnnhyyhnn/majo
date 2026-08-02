package com.agent.coding.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.time.Instant;
import java.util.*;

/**
 * File-backed store for console inbox events, mirroring qwenpaw's
 * {@code app/inbox_store.py}. Events live in {@code <WORKING_DIR>/inbox_events.json}
 * as a JSON list (newest-first), capped at {@value #MAX_EVENTS} entries and
 * written atomically under an advisory file lock so concurrent reads/writes are
 * safe.
 *
 * <p>Since this is the console subsystem (not the skill subsystem), the file
 * lives in the workspace root (same WORKING_DIR the skills subsystem uses)
 * rather than under {@code skill_pool/}. This matches qwenpaw, which also
 * writes it to WORKING_DIR.
 */
public final class InboxStore {

    private static final Logger log = LoggerFactory.getLogger(InboxStore.class);

    private static final Path WORKING_DIR =
            Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final Path INBOX_PATH = WORKING_DIR.resolve("inbox_events.json");
    private static final int MAX_EVENTS = 5000;

    private InboxStore() {}

    // ------------------------------------------------------------------
    // File I/O (advisory lock + atomic replace) — matches SkillStore helpers
    // ------------------------------------------------------------------

    private static Path lockPathFor(Path jsonPath) {
        return jsonPath.getParent().resolve("." + jsonPath.getFileName() + ".lock");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> loadEvents() {
        if (!Files.isRegularFile(INBOX_PATH)) {
            return new ArrayList<>();
        }
        try {
            String text = Files.readString(INBOX_PATH, StandardCharsets.UTF_8);
            if (text.isBlank()) return new ArrayList<>();
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Object.class);
            if (!(parsed instanceof List<?> list)) return new ArrayList<>();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() != null) row.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    result.add(row);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load inbox events from {}: {}", INBOX_PATH, e.getMessage());
            return new ArrayList<>();
        }
    }

    private static void saveEvents(List<Map<String, Object>> events) {
        try {
            Files.createDirectories(INBOX_PATH.getParent());
        } catch (Exception e) {
            log.warn("Cannot create dir for {}", INBOX_PATH, e);
        }
        Path temp = INBOX_PATH.resolveSibling(INBOX_PATH.getFileName() + ".tmp");
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(events);
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, INBOX_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, INBOX_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Failed to write inbox events to {}", INBOX_PATH, e);
        }
    }

    /** read-modify-write under one advisory lock (no nested locking). */
    private static <T> T mutate(java.util.function.Function<List<Map<String, Object>>, T> mutator) {
        try (FileChannel channel = FileChannel.open(lockPath(INBOX_PATH),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            List<Map<String, Object>> events = loadEvents();
            T result = mutator.apply(events);
            saveEvents(events);
            return result;
        } catch (Exception e) {
            log.warn("Inbox mutation failed: {}", e.getMessage());
            return null;
        }
    }

    private static Path lockPath(Path jsonPath) {
        return jsonPath.getParent().resolve("." + jsonPath.getFileName() + ".lock");
    }

    // ------------------------------------------------------------------
    // Public API  —  port of inbox_store.append_event/list_events/mark_*/
    // ------------------------------------------------------------------

    public static Map<String, Object> appendEvent(
            String agentId,
            String sourceType,
            String sourceId,
            String eventType,
            String status,
            String title,
            String body,
            String severity,
            Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("agent_id", (agentId == null || agentId.isBlank()) ? "default" : agentId);
        event.put("source_type", sourceType);
        event.put("source_id", sourceId == null ? "" : sourceId);
        event.put("event_type", eventType);
        event.put("status", status);
        event.put("severity", (severity == null || severity.isBlank()) ? "info" : severity);
        event.put("title", title);
        event.put("body", body);
        event.put("payload", payload == null ? Map.of() : payload);
        event.put("read", false);
        event.put("created_at", Instant.now().getEpochSecond() + Instant.now().getNano() / 1e9);
        mutate(events -> {
            events.add(0, event);
            while (events.size() > MAX_EVENTS) events.remove(events.size() - 1);
            return event;
        });
        return event;
    }

    public static List<Map<String, Object>> listEvents(
            int limit, int offset, String sourceType, String status, String agentId, boolean unreadOnly) {
        List<Map<String, Object>> events = loadEvents();
        if (sourceType != null && !sourceType.isBlank()) {
            events = events.stream().filter(e -> sourceType.equals(e.get("source_type"))).toList();
        }
        if (status != null && !status.isBlank()) {
            events = events.stream().filter(e -> status.equals(e.get("status"))).toList();
        }
        if (agentId != null && !agentId.isBlank()) {
            events = events.stream().filter(e -> agentId.equals(e.get("agent_id"))).toList();
        }
        if (unreadOnly) {
            events = events.stream().filter(e -> !Boolean.TRUE.equals(e.get("read"))).toList();
        }
        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.max(limit, 0);
        if (safeOffset >= events.size()) {
            return List.of();
        }
        int end = Math.min(events.size(), safeOffset + safeLimit);
        return new ArrayList<>(events.subList(safeOffset, end));
    }

    public static int markRead(List<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) return 0;
        Set<String> idSet = new HashSet<>(eventIds);
        Integer updated = mutate(events -> {
            int n = 0;
            // iterate in reverse-safe copy; remove-modify the same collection
            for (int i = 0; i < events.size(); i++) {
                Map<String, Object> e = events.get(i);
                if (idSet.contains(e.get("id")) && !Boolean.TRUE.equals(e.get("read"))) {
                    e.put("read", true);
                    n++;
                }
            }
            return n;
        });
        return updated == null ? 0 : updated;
    }

    public static int markAllRead() {
        Integer result = mutate(events -> {
            int n = 0;
            for (Map<String, Object> e : events) {
                if (!Boolean.TRUE.equals(e.get("read"))) {
                    e.put("read", true);
                    n++;
                }
            }
            return n;
        });
        return result == null ? 0 : result;
    }

    /** Deletes an event, returning (deleted, runId, runIdStillReferenced). */
    public static DeletedEvent deleteEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return new DeletedEvent(false, null, false);
        }
        return mutate(events -> {
            boolean deleted = false;
            String deletedRunId = null;
            for (Iterator<Map<String, Object>> it = events.iterator(); it.hasNext(); ) {
                Map<String, Object> e = it.next();
                if (!deleted && eventId.equals(e.get("id"))) {
                    Object payload = e.get("payload");
                    if (payload instanceof Map<?, ?> pm && pm.get("run_id") instanceof String rid) {
                        deletedRunId = rid;
                    }
                    deleted = true;
                    it.remove();
                    break;
                }
            }
            boolean stillReferenced = false;
            if (deleted && deletedRunId != null && deletedRunId.length() > 0) {
                for (Map<String, Object> e : events) {
                    Object payload = e.get("payload");
                    if (payload instanceof Map<?, ?> pm && deletedRunId.equals(pm.get("run_id"))) {
                        stillReferenced = true;
                        break;
                    }
                }
            }
            return new DeletedEvent(deleted, deletedRunId, stillReferenced);
        });
    }

    public record DeletedEvent(boolean deleted, String runId, boolean runIdStillReferenced) {}
}