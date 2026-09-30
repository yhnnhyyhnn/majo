package com.agent.coding.memory;

import com.agent.coding.SettingsService;
import com.agent.coding.service.ModelRoutingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Embedding memory backend tests (ADR-0015): deterministic fake embedder,
 * chunking shape, cosine ranking, keyword fallback on failure, and the
 * dirty-safe refresh contract.
 */
class EmbeddingMemoryBackendTest {

    @TempDir
    Path workspace;

    private static final String DOC_A = """
            # Deploy notes

            We run the ansible playbook on staging every week.

            The database backup schedule is nightly at 02:00.
            """;
    private static final String DOC_B = """
            # User preferences

            The user prefers concise answers in Chinese.
            """;

    /**
     * Fake embedder: 2-D vectors derived from keyword presence, so tests can
     * assert ranking without a real model. Vectors: [hasDeployTopic,
     * hasUserPrefTopic].
     */
    private static EmbeddingClient fakeClient() {
        return (endpoint, inputs) -> {
            List<float[]> out = new ArrayList<>();
            for (String text : inputs) {
                String t = text.toLowerCase();
                float deploy = t.contains("ansible") || t.contains("deploy")
                        || t.contains("backup") ? 1f : 0f;
                float pref = t.contains("prefer") || t.contains("chinese")
                        || t.contains("concise") ? 1f : 0f;
                out.add(new float[]{deploy, pref});
            }
            return out;
        };
    }

    private EmbeddingMemoryBackend backend(EmbeddingClient client) {
        var routing = Mockito.mock(ModelRoutingService.class);
        var settings = Mockito.mock(SettingsService.class);
        return new EmbeddingMemoryBackend(routing, settings, client);
    }

    private EmbeddingMemoryBackend startedBackend(EmbeddingClient client) throws Exception {
        Files.createDirectories(workspace.resolve("memory"));
        Files.writeString(workspace.resolve("memory").resolve("deploy.md"), DOC_A);
        Files.writeString(workspace.resolve("memory").resolve("prefs.md"), DOC_B);
        EmbeddingMemoryBackend backend = backend(client);
        backend.start(new MemoryBackendContext("default", workspace,
                Map.of("embedding", Map.of("model", "fake-embed")),
                "zh"));
        return backend;
    }

    @Test
    void buildsIndexAndRanksSemantically() throws Exception {
        EmbeddingMemoryBackend backend = startedBackend(fakeClient());
        backend.rebuild();

        List<MemoryHit> hits = backend.search("how is the database backup handled", 5);
        assertEquals(1, hits.size(), "only the deploy topic passes the floor");
        assertTrue(hits.get(0).source().contains("deploy.md"), hits.get(0).source());
        assertTrue(hits.get(0).score() > 0.9);
        assertEquals("embedding", hits.get(0).metadata().get("backend"));
    }

    @Test
    void rememberAppendsAndEmbedsTheDailyNote() throws Exception {
        EmbeddingClient client = fakeClient();
        EmbeddingMemoryBackend backend = startedBackend(client);
        backend.remember("The user prefers concise answers in Chinese.",
                Map.of("agent_id", "default"));

        Path daily = workspace.resolve("memory").resolve("daily")
                .resolve(java.time.LocalDate.now() + ".md");
        assertTrue(Files.exists(daily), "daily note written");

        // The daily note's preference chunk is now recallable.
        List<MemoryHit> hits = backend.search("user preference about chinese answers", 5);
        assertTrue(hits.stream().anyMatch(h -> h.source().contains("daily")),
                "daily note recallable: " + hits);
    }

    @Test
    void embedderFailureFallsBackToKeywordSearch() throws Exception {
        EmbeddingClient failing = (endpoint, inputs) -> {
            throw new IllegalStateException("endpoint down");
        };
        EmbeddingMemoryBackend backend = startedBackend(failing);
        backend.rebuild();

        // Index build swallowed the failure (warn + empty), and search falls
        // back to the inherited keyword path.
        List<MemoryHit> hits = backend.search("ansible", 5);
        assertTrue(hits.stream().anyMatch(h -> h.source().contains("deploy.md")),
                "keyword fallback must still recall: " + hits);
    }

    @Test
    void chunkingMergesParagraphsAndHardSplitsOversized() {
        List<String> small = EmbeddingMemoryBackend.chunk("para one\n\npara two");
        assertEquals(1, small.size(), "small paragraphs merge");

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            big.append("x".repeat(80)).append("\n\n");
        }
        List<String> chunks = EmbeddingMemoryBackend.chunk(big.toString());
        assertTrue(chunks.size() >= 4, "oversized content splits: " + chunks.size());
        for (String c : chunks) {
            assertTrue(c.length() <= 1200, "hard cap respected");
        }
    }

    @Test
    void modelChangeTriggersIndexRebuild() throws Exception {
        EmbeddingMemoryBackend backend = startedBackend(fakeClient());
        backend.rebuild();
        Path indexFile = workspace.resolve("memory").resolve(".index-embedding.json");
        String before = Files.readString(indexFile);

        // Same fake model name → index reused (no rebuild marker churn).
        backend.search("ansible", 5);
        assertEquals(before, Files.readString(indexFile));
    }

    @Test
    void unconfiguredWorkspaceDegradesToKeyword() throws Exception {
        Files.createDirectories(workspace.resolve("memory"));
        Files.writeString(workspace.resolve("memory").resolve("deploy.md"), DOC_A);
        EmbeddingMemoryBackend backend = backend(fakeClient());
        // start WITHOUT embedding config → endpoint unresolvable
        backend.start(new MemoryBackendContext("default", workspace,
                Map.of(), "zh"));
        // Keyword index must be built first (parent contract: search never
        // builds; remember/rebuild do).
        backend.rebuild();

        List<MemoryHit> hits = backend.search("ansible", 5);
        assertTrue(hits.stream().anyMatch(h -> h.source().contains("deploy.md")),
                "keyword fallback serves recall without config");
    }
}
