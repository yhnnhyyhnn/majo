package com.agent.coding.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the memory backend SPI (ADR-0008): registry selection with
 * fallback, and the keyword default backend's index/search cycle.
 */
class MemoryBackendSpiTest {

    @TempDir
    Path workspace;

    // ── Keyword backend ──────────────────────────────────────────────

    private KeywordMemoryBackend startedBackend() throws Exception {
        Path memory = workspace.resolve("memory");
        Files.createDirectories(memory);
        Files.writeString(memory.resolve("deploy.md"),
                "# Deploy notes\nRun the ansible playbook on staging servers weekly.");
        Files.writeString(workspace.resolve("profile.md"),
                "# Profile\nThe user prefers concise answers in Chinese.");
        KeywordMemoryBackend backend = new KeywordMemoryBackend();
        backend.start(new MemoryBackendContext("default", workspace, Map.of(), "zh"));
        return backend;
    }

    @Test
    void rebuildBuildsIndexAndSearchFindsMatches() throws Exception {
        KeywordMemoryBackend backend = startedBackend();

        Map<String, Object> report = backend.rebuild();
        assertEquals("completed", report.get("status"));
        assertEquals(2, report.get("indexed_files"));

        List<MemoryHit> hits = backend.search("ansible", 5);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).source().contains("deploy.md"));
        assertTrue(hits.get(0).snippet().contains("ansible") || hits.get(0).snippet().isBlank());
    }

    @Test
    void searchWithUnknownTermReturnsEmpty() throws Exception {
        KeywordMemoryBackend backend = startedBackend();
        backend.rebuild();
        assertTrue(backend.search("zzzunrelated", 5).isEmpty());
    }

    @Test
    void persistedIndexSurvivesRestart() throws Exception {
        KeywordMemoryBackend first = startedBackend();
        first.rebuild();
        assertTrue(Files.exists(workspace.resolve("memory").resolve(".index.json")));

        // New instance, no in-memory snapshot — loads the persisted index.
        KeywordMemoryBackend second = new KeywordMemoryBackend();
        second.start(new MemoryBackendContext("default", workspace, Map.of(), "zh"));
        assertEquals(1, second.search("ansible", 5).size());
    }

    // ── Registry selection & fallback ────────────────────────────────

    private static class FakeBackend implements MemoryBackend {
        private final String id;
        volatile boolean available = true;

        FakeBackend(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public void start(MemoryBackendContext context) {}
        @Override public void close() {}
        @Override public boolean isAvailable() { return available; }
        @Override public String getMemoryPrompt() { return ""; }
        @Override public List<MemoryHit> search(String query, int maxResults) { return List.of(); }
        @Override public void remember(String content, Map<String, Object> metadata) {}
    }

    @Test
    void registryListsBackendsInOrder() {
        KeywordMemoryBackend keyword = new KeywordMemoryBackend();
        FakeBackend extra = new FakeBackend("zextra");
        MemoryBackendRegistry registry = new MemoryBackendRegistry(List.of(keyword, extra));
        assertEquals(List.of("keyword", "zextra"), registry.backendIds());
    }

    @Test
    void registryDropsDuplicateIds() {
        MemoryBackendRegistry registry = new MemoryBackendRegistry(
                List.of(new KeywordMemoryBackend(), new KeywordMemoryBackend()));
        assertEquals(List.of("keyword"), registry.backendIds());
    }

    @Test
    void keywordIsAlwaysAvailable() throws Exception {
        KeywordMemoryBackend backend = startedBackend();
        assertTrue(backend.isAvailable());
        assertEquals("keyword", backend.id());
        assertNotNull(backend.getMemoryPrompt());
    }
}
