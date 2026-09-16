package com.agent.coding.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Summary backend behavior (ADR-0014): LLM extraction precedes the write,
 * failures degrade to raw excerpts (never lose a batch), and metadata
 * records which path was taken. Also covers keyword-backend agent routing.
 */
class SummaryMemoryBackendTest {

    @TempDir
    Path workspace;

    private SummaryMemoryBackend started(MemorySummarizer summarizer) throws Exception {
        SummaryMemoryBackend backend = new SummaryMemoryBackend(summarizer);
        backend.start(new MemoryBackendContext("default", workspace, Map.of(), "zh"));
        return backend;
    }

    private String dailyNote() throws Exception {
        Path daily = workspace.resolve("memory").resolve("daily");
        try (var s = Files.list(daily)) {
            Path file = s.findFirst().orElseThrow();
            return Files.readString(file);
        }
    }

    @Test
    void summarizedContentIsWrittenInsteadOfRaw() throws Exception {
        SummaryMemoryBackend backend = started((agentId, content) ->
                Optional.of("- 用户的生产数据库是 db-prod-07"));
        backend.remember("会话原文: 我查了下, 生产库在 db-prod-07, 顺便看了天气",
                Map.of("agent_id", "default"));

        String note = dailyNote();
        assertTrue(note.contains("db-prod-07"), note);
        assertFalse(note.contains("天气"), "raw chatter must not land: " + note);
        assertTrue(note.contains("llm_summary"), note);

        // Immediately searchable through the inherited index.
        assertEquals(1, backend.search("db-prod-07", 5).size());
    }

    @Test
    void summarizerFailureFallsBackToRawExcerpt() throws Exception {
        SummaryMemoryBackend backend = started((agentId, content) -> {
            throw new IllegalStateException("model down");
        });
        backend.remember("原始轮次内容 marker: fallback-check-42", Map.of("agent_id", "default"));

        String note = dailyNote();
        assertTrue(note.contains("fallback-check-42"), note);
        assertTrue(note.contains("raw_excerpt"), note);
    }

    @Test
    void emptySummaryFallsBackToRawExcerpt() throws Exception {
        SummaryMemoryBackend backend = started((agentId, content) -> Optional.empty());
        backend.remember("原始轮次内容 marker: empty-summary", Map.of("agent_id", "default"));

        String note = dailyNote();
        assertTrue(note.contains("empty-summary"), note);
        assertTrue(note.contains("raw_excerpt"), note);
    }

    @Test
    void idIsSummaryAndAlwaysAvailable() throws Exception {
        SummaryMemoryBackend backend = started((a, c) -> Optional.empty());
        assertEquals("summary", backend.id());
        assertTrue(backend.isAvailable());
    }

    @Test
    void keywordBackendRoutesByAgentId() throws Exception {
        KeywordMemoryBackend backend = new KeywordMemoryBackend();
        backend.start(new MemoryBackendContext("agent-a", workspace.resolve("a"), Map.of(), "zh"));
        backend.start(new MemoryBackendContext("agent-b", workspace.resolve("b"), Map.of(), "zh"));

        backend.remember("secret of agent a", Map.of("agent_id", "agent-a"));

        assertTrue(Files.exists(workspace.resolve("a/memory/daily")), "agent-a got the note");
        try (var s = Files.list(workspace.resolve("a/memory/daily"))) {
            assertTrue(s.findFirst().isPresent());
        }
        assertFalse(Files.exists(workspace.resolve("b/memory")), "agent-b untouched");

        // No agent_id → legacy behavior writes to every started workspace.
        backend.remember("broadcast note", Map.of());
        assertTrue(Files.exists(workspace.resolve("b/memory/daily")));
    }
}
