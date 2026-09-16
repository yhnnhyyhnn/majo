package com.agent.coding.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the memory write pipeline pieces (ADR-0009): keyword backend
 * remember() daily notes + reindex, and the /memory command surface
 * (status/list/search/read/forget/write + path-traversal rejection).
 */
class MemoryWriteAndCommandTest {

    @TempDir
    Path workspace;

    private KeywordMemoryBackend startedBackend() throws Exception {
        Path memory = workspace.resolve("memory");
        Files.createDirectories(memory);
        Files.writeString(memory.resolve("deploy.md"),
                "# Deploy notes\nRun the ansible playbook on staging servers weekly.");
        KeywordMemoryBackend backend = new KeywordMemoryBackend();
        backend.start(new MemoryBackendContext("default", workspace, Map.of(), "zh"));
        return backend;
    }

    // ── remember() (ADR-0009 write side) ─────────────────────────────

    @Test
    void rememberWritesDailyNoteAndMakesItSearchable() throws Exception {
        KeywordMemoryBackend backend = startedBackend();
        backend.rebuild();

        backend.remember("The production database host is db-prod-07", Map.of("trigger", "manual"));

        Path daily = workspace.resolve("memory").resolve("daily");
        assertTrue(Files.isDirectory(daily), "daily dir created");
        List<Path> notes;
        try (var stream = Files.list(daily)) {
            notes = stream.toList();
        }
        assertEquals(1, notes.size());
        String note = Files.readString(notes.get(0));
        assertTrue(note.contains("db-prod-07"));
        assertTrue(note.contains("manual"), "metadata recorded");

        // The new note is immediately retrievable.
        List<MemoryHit> hits = backend.search("db-prod-07", 5);
        assertEquals(1, hits.size());
    }

    @Test
    void rememberBlankContentIsNoop() throws Exception {
        KeywordMemoryBackend backend = startedBackend();
        backend.remember("   ", Map.of());
        Path daily = workspace.resolve("memory").resolve("daily");
        assertFalse(Files.exists(daily), "no daily dir for blank content");
    }

    @Test
    void getMemoryPromptIsNonEmptyGuidance() {
        assertNotNull(new KeywordMemoryBackend().getMemoryPrompt());
        assertFalse(new KeywordMemoryBackend().getMemoryPrompt().isBlank());
    }

    // ── /memory command surface ──────────────────────────────────────

    private MemoryCommandService service(KeywordMemoryBackend backend) {
        return new MemoryCommandService(new MemoryBackendRegistry(List.of(backend))) {
            @Override
            protected MemoryBackend resolveBackend(String agentId) {
                return backend;
            }

            @Override
            protected java.nio.file.Path workspaceOf(String agentId) {
                return workspace;
            }
        };
    }

    @Test
    void statusReportsBackendAndSubcommands() throws Exception {
        MemoryCommandService svc = service(startedBackend());
        String out = svc.execute("default", "");
        assertTrue(out.contains("Memory 状态"));
        assertTrue(out.contains("keyword"));
        assertTrue(out.contains("search"));
    }

    @Test
    void writeThenSearchThenReadRoundTrip() throws Exception {
        MemoryCommandService svc = service(startedBackend());

        String wrote = svc.execute("default", "write 用户的生产数据库是 db-prod-07");
        assertTrue(wrote.contains("已写入"));

        String found = svc.execute("default", "search db-prod-07");
        assertTrue(found.contains("找到"), found);
        String pathLine = found.lines()
                .filter(l -> l.contains("`"))
                .findFirst().orElseThrow();
        String relPath = pathLine.substring(pathLine.indexOf('`') + 1,
                pathLine.indexOf('`', pathLine.indexOf('`') + 1));

        String read = svc.execute("default", "read " + relPath);
        assertTrue(read.contains("db-prod-07"));
    }

    @Test
    void forgetRemovesNoteAndReindexes() throws Exception {
        KeywordMemoryBackend backend = startedBackend();
        backend.rebuild();
        backend.remember("obsolete note about zzztopic", Map.of());
        MemoryCommandService svc = service(backend);

        String listed = svc.execute("default", "list");
        assertTrue(listed.contains("daily/"), listed);
        String relPath = listed.lines()
                .filter(l -> l.contains("`"))
                .findFirst().orElseThrow();
        relPath = relPath.substring(relPath.indexOf('`') + 1,
                relPath.indexOf('`', relPath.indexOf('`') + 1));

        String forgot = svc.execute("default", "forget " + relPath);
        assertTrue(forgot.contains("已删除"));
        assertTrue(backend.search("zzztopic", 5).isEmpty());
    }

    @Test
    void forgetRejectsPathTraversal() throws Exception {
        MemoryCommandService svc = service(startedBackend());
        Path secret = workspace.resolve("secret.txt");
        Files.writeString(secret, "top secret");

        String out = svc.execute("default", "forget ../secret.txt");
        assertTrue(out.contains("用法") || out.contains("路径"), out);
        assertTrue(Files.exists(secret), "outside file untouched");
    }

    @Test
    void unknownSubcommandShowsStatus() throws Exception {
        MemoryCommandService svc = service(startedBackend());
        String out = svc.execute("default", "bogus");
        assertTrue(out.contains("未知子命令"));
        assertTrue(out.contains("Memory 状态"));
    }
}
