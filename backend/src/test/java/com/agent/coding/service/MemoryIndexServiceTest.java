package com.agent.coding.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests MemoryIndexService: keyword index construction over workspace
 * memory/ files, persistence to .index.json, and search lookup.
 */
class MemoryIndexServiceTest {

    @Test
    void rebuildIndexesMemoryFiles() throws IOException {
        Path ws = Files.createTempDirectory("majo-mem-test");
        Path memory = ws.resolve("memory");
        Files.createDirectories(memory);
        Files.writeString(memory.resolve("notes.md"),
                "The login flow requires a token and a refresh token.", StandardCharsets.UTF_8);
        Files.writeString(memory.resolve("daily").resolve("2026-01-01.md")
                        .toAbsolutePath().getParent() != null
                ? createDirs(memory, "daily").resolve("2026-01-01.md")
                : memory.resolve("daily").resolve("2026-01-01.md"),
                "Fixed the authentication bug in the login handler.", StandardCharsets.UTF_8);

        MemoryIndexService service = new MemoryIndexService();
        Map<String, Object> result = service.rebuildForPath(ws);

        assertEquals("completed", result.get("status"));
        assertEquals(2, result.get("indexed_files"));
        assertTrue((Integer) result.get("keywords") > 0);
        assertTrue(Files.isRegularFile(memory.resolve(".index.json")));

        List<String> hits = service.searchForPath(ws, "login");
        assertFalse(hits.isEmpty());
    }

    @Test
    void rebuildWithNoFilesIsEmpty() throws IOException {
        Path ws = Files.createTempDirectory("majo-mem-empty");
        Files.createDirectories(ws.resolve("memory"));
        MemoryIndexService service = new MemoryIndexService();
        Map<String, Object> result = service.rebuildForPath(ws);
        assertEquals(0, result.get("indexed_files"));
        assertEquals(0, result.get("keywords"));
    }

    private static Path createDirs(Path base, String sub) throws IOException {
        Path dir = base.resolve(sub);
        Files.createDirectories(dir);
        return dir;
    }
}
