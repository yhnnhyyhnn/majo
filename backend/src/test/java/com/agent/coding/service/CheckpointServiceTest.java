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
 * Tests CheckpointService: git-backed snapshots, refs, session heads,
 * graph entries, restore and GC.
 */
class CheckpointServiceTest {

    private final CheckpointService service = new CheckpointService();

    @Test
    void snapshotCreatesRefAndHead() throws IOException {
        Path ws = Files.createTempDirectory("majo-cp-snap");
        Files.writeString(ws.resolve("a.txt"), "v1", StandardCharsets.UTF_8);

        Map<String, Object> snap = service.makeSnapshot(ws, "snap", "sess-1", "u1", "console",
                "first", "first snapshot");

        assertEquals("snap", snap.get("kind"));
        String ref = (String) snap.get("ref");
        assertTrue(ref.startsWith("refs/snap/"));
        String commit = (String) snap.get("commit");
        assertTrue(service.refExists(ws, ref));
        assertEquals(commit, service.sessionHead(ws, (String) snap.get("session_key")));
    }

    @Test
    void graphListsSnapshots() throws IOException, InterruptedException {
        Path ws = Files.createTempDirectory("majo-cp-graph");
        Files.writeString(ws.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        service.makeSnapshot(ws, "snap", "sess-1", "u1", "console", "one", "one");
        Thread.sleep(1100);
        Files.writeString(ws.resolve("a.txt"), "world", StandardCharsets.UTF_8);
        service.makeSnapshot(ws, "snap", "sess-1", "u1", "console", "two", "two");

        List<Map<String, Object>> nodes = service.graphEntries(ws);
        assertEquals(2, nodes.size());
        assertEquals("snap", nodes.get(0).get("kind"));
        assertEquals("two", nodes.get(0).get("name"));
        assertEquals("one", nodes.get(1).get("name"));
    }

    @Test
    void restoreRecoversFileState() throws IOException {
        Path ws = Files.createTempDirectory("majo-cp-restore");
        Files.writeString(ws.resolve("a.txt"), "v1", StandardCharsets.UTF_8);
        Map<String, Object> snap = service.makeSnapshot(ws, "snap", "sess-1", "u1", "console",
                "v1", "v1");
        Files.writeString(ws.resolve("a.txt"), "v2-changed", StandardCharsets.UTF_8);

        service.restoreFiles(ws, (String) snap.get("commit"));

        assertEquals("v1", Files.readString(ws.resolve("a.txt")));
    }

    @Test
    void gcDeletesOldRefs() throws IOException {
        Path ws = Files.createTempDirectory("majo-cp-gc");
        Files.writeString(ws.resolve("a.txt"), "x", StandardCharsets.UTF_8);
        service.makeSnapshot(ws, "snap", "sess-1", "u1", "console", "one", "one");
        Files.writeString(ws.resolve("a.txt"), "y", StandardCharsets.UTF_8);
        service.makeSnapshot(ws, "snap", "sess-1", "u1", "console", "two", "two");

        List<String> deleted = service.gc(ws, false, 1, null);
        assertEquals(1, deleted.size());
        assertEquals(1, service.listRefs(ws).size());
    }

    @Test
    void resetClearsEverything() throws IOException {
        Path ws = Files.createTempDirectory("majo-cp-reset");
        Files.writeString(ws.resolve("a.txt"), "x", StandardCharsets.UTF_8);
        service.makeSnapshot(ws, "snap", "sess-1", "u1", "console", "one", "one");
        service.reset(ws);
        assertEquals(0, service.listRefs(ws).size());
        assertTrue(service.loadHeads(ws).isEmpty());
    }

    @Test
    void sessionKeyIsStable() {
        String k1 = service.sessionKey("console", "u1", "sess-1");
        String k2 = service.sessionKey("console", "u1", "sess-1");
        assertEquals(k1, k2);
        assertNotEquals(k1, service.sessionKey("console", "u1", "sess-2"));
    }

    @Test
    void gcSettingsRoundTrip() throws IOException {
        Path ws = Files.createTempDirectory("majo-cp-cfg");
        service.saveGcSettings(ws, 5, 10, 2);
        Map<String, Object> loaded = service.gcSettings(ws);
        assertEquals(5, ((Number) loaded.get("gc_keep_count")).intValue());
        assertEquals(10, ((Number) loaded.get("gc_keep_days")).intValue());
        assertEquals(2, ((Number) loaded.get("pre_restore_retention_days")).intValue());
    }
}
