package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.memory.KeywordMemoryBackend;
import com.agent.coding.memory.MemoryBackendRegistry;
import com.agent.coding.memory.SummaryMemoryBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Memory backend switch convergence (QwenPaw #7893 port): changing
 * {@code memory_manager_backend} through the running-config API must take
 * effect on the next resolve — the registry's per-agent cache is evicted
 * and re-resolved within the same request instead of serving the previous
 * backend until restart. An unavailable/unknown selection converges to the
 * registry's fallback immediately.
 */
class MemoryBackendConvergenceTest {

    private static final String AGENT = "default";

    private Map<String, Object> originalRunning;
    private MemoryBackendRegistry registry;
    private WorkspaceController controller;

    @BeforeEach
    void setUp() {
        originalRunning = AgentStore.getRunningConfig(AGENT);
        registry = new MemoryBackendRegistry(List.of(
                new KeywordMemoryBackend(),
                new SummaryMemoryBackend((agentId, content) -> java.util.Optional.empty())));
        controller = new WorkspaceController(
                null, null, null, null, null, null, registry);
    }

    @AfterEach
    void tearDown() {
        AgentStore.saveRunningConfig(AGENT, originalRunning, null);
    }

    private void putBackend(String backendId) {
        Map<String, Object> body = new LinkedHashMap<>(AgentStore.getRunningConfig(AGENT));
        body.put("memory_manager_backend", backendId);
        controller.runningConfigUpdate(AGENT, body);
    }

    @Test
    void backendSwitchTakesEffectWithoutRestart() {
        putBackend("keyword");
        assertEquals("keyword", registry.resolve(AGENT).id());

        // Before the fix the cached keyword backend kept being served here:
        // nothing evicted the registry cache when the config changed.
        putBackend("summary");
        assertEquals("summary", registry.resolve(AGENT).id());

        putBackend("keyword");
        assertEquals("keyword", registry.resolve(AGENT).id());
    }

    @Test
    void unavailableBackendFallsBackImmediately() {
        putBackend("keyword");
        assertEquals("keyword", registry.resolve(AGENT).id());

        putBackend("no-such-backend");
        String resolved = registry.resolve(AGENT).id();
        assertNotEquals("no-such-backend", resolved);
        assertEquals("keyword", resolved);
    }
}
