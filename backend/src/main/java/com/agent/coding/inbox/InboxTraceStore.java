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
import java.util.*;

/**
 * File-backed store for per-run execution traces,'s
 * {@code app/inbox_trace_store.py}. Each run is a JSON file
 * {@code <WORKING_DIR>/inbox_traces/{run_id}.json}. A trace starts with
 * {@code status="running"}, accumulates {@code events}, and is finalized with a
 * terminal status and {@code completed_at}.
 */
public final class InboxTraceStore {

    private static final Logger log = LoggerFactory.getLogger(InboxTraceStore.class);

    private static final Path TRACE_DIR = com.agent.coding.skill.SkillStore.WORKING_DIR.resolve("inbox_traces");

    private InboxTraceStore() {}

    private static Path tracePath(String runId) {
        return TRACE_DIR.resolve(runId + ".json");
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static Path lockPath(Path jsonPath) {
        return jsonPath.getParent().resolve("." + jsonPath.getFileName() + ".lock");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readTraceUnlocked(String runId) {
        Path path = tracePath(runId);
        if (!Files.isRegularFile(path)) {
            Map<String, Object> created = new java.util.LinkedHashMap<>();
            created.put("run_id", runId);
            created.put("created_at", now());
            created.put("completed_at", null);
            created.put("status", "running");
            created.put("meta", new java.util.LinkedHashMap<String, Object>());
            created.put("events", new java.util.ArrayList<>());
            return created;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Object.class);
            if (!(parsed instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("invalid trace file");
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) row.put(String.valueOf(e.getKey()), e.getValue());
            }
            row.putIfAbsent("events", new java.util.ArrayList<>());
            return row;
        } catch (Exception e) {
            throw new IllegalStateException("invalid trace file");
        }
    }

    private static void writeTraceUnlocked(String runId, Map<String, Object> payload) {
        Path path = tracePath(runId);
        try {
            Files.createDirectories(path.getParent());
        } catch (Exception e) {
            log.warn("Cannot create trace dir for {}", path, e);
        }
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Failed to write trace {} to {}", runId, path, e);
        }
    }

    private static <T> T mutate(String runId, java.util.function.Function<Map<String, Object>, T> fn) {
        Path path = tracePath(runId);
        try (FileChannel channel = FileChannel.open(lockPath(path),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Map<String, Object> payload = readTraceUnlocked(runId);
            T result = fn.apply(payload);
            writeTraceUnlocked(runId, payload);
            return result;
        } catch (Exception e) {
            log.warn("Trace mutation failed for {}: {}", runId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Public API  —  port of inbox_trace_store create/append/finalize/get/delete
    // ------------------------------------------------------------------

    public static void createTrace(String runId, Map<String, Object> meta) {
        mutate(runId, payload -> {
            payload.put("created_at", now());
            payload.put("completed_at", null);
            payload.put("status", "running");
            payload.put("meta", meta == null ? Map.of() : meta);
            payload.put("events", new java.util.ArrayList<>());
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    public static void appendTraceEvents(String runId, List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) return;
        mutate(runId, payload -> {
            List<Object> existing = (List<Object>) payload.computeIfAbsent("events", k -> new ArrayList<>());
            for (Map<String, Object> item : events) {
                Map<String, Object> normalized = new java.util.LinkedHashMap<>();
                Object at = item.get("at");
                normalized.put("at", at != null ? at : now());
                normalized.put("event", item.get("event"));
                existing.add(normalized);
            }
            return null;
        });
    }

    public static void finalizeTrace(String runId, String status, String error) {
        mutate(runId, payload -> {
            payload.put("status", status);
            payload.put("completed_at", now());
            if (error != null) payload.put("error", error);
            return null;
        });
    }

    /** Returns the trace map, or null when the trace file does not exist. */
    public static Map<String, Object> getTrace(String runId) {
        Path path = tracePath(runId);
        if (!Files.isRegularFile(path)) return null;
        try (FileChannel channel = FileChannel.open(lockPath(path),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return readTraceUnlocked(runId);
        } catch (Exception e) {
            log.warn("Failed to read trace {}: {}", runId, e.getMessage());
            return null;
        }
    }

    public static boolean deleteTrace(String runId) {
        if (runId == null || runId.isBlank()) return false;
        Path path = tracePath(runId);
        try (FileChannel channel = FileChannel.open(lockPath(path),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            if (!Files.isRegularFile(path)) return false;
            Files.delete(path);
            return true;
        } catch (java.nio.file.NoSuchFileException e) {
            return false;
        } catch (Exception e) {
            log.warn("Failed to delete trace {}: {}", runId, e.getMessage());
            return false;
        }
    }
}
