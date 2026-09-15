package com.agent.coding.memory;

import java.util.List;
import java.util.Map;

/**
 * Pluggable long-term memory backend (ADR-0008). Java-side counterpart of
 * QwenPaw's BaseMemoryManager: lifecycle is {@link #start} → use
 * ({@link #search}, {@link #remember}) → {@link #close}.
 *
 * <p>Implementations are Spring beans; {@link MemoryBackendRegistry} collects
 * them at startup and routes per-agent by the configured backend id, falling
 * back to the next registered backend when the selected one reports
 * {@link #isAvailable()} == false (QwenPaw #7544/#7663 degradation semantics).
 */
public interface MemoryBackend {

    /** Stable backend id used by the {@code memory_manager_backend} config. */
    String id();

    /** Initialize storage for one agent workspace. Called once per agent. */
    void start(MemoryBackendContext context);

    /** Flush and release resources. Must tolerate repeated calls. */
    void close();

    /** Whether the backend is healthy and usable right now. */
    boolean isAvailable();

    /** Memory guidance prompt for the system prompt (empty string = none). */
    String getMemoryPrompt();

    /** Recall memories relevant to the query, best match first. */
    List<MemoryHit> search(String query, int maxResults);

    /** Persist one memory entry. */
    void remember(String content, Map<String, Object> metadata);

    /** Rebuild derived structures (indexes etc.); returns a status report. */
    default Map<String, Object> rebuild() {
        return Map.of("status", "unsupported");
    }
}
