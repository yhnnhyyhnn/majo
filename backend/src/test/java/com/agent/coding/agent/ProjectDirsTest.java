package com.agent.coding.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-folder default workspaces (QwenPaw #7789 counterpart): ordered
 * project_dirs with primary mirroring, directory validation, and the
 * promote-on-set semantics of setProjectDir.
 */
class ProjectDirsTest {

    @TempDir
    Path dirA;

    @TempDir
    Path dirB;

    @Test
    void setProjectDirsValidatesPersistsAndMirrorsPrimary() {
        String agentId = "default";
        AgentStore.setProjectDirs(agentId, List.of(
                Map.of("path", dirA.toString(), "label", "A"),
                Map.of("path", "/nonexistent/zzz", "label", "bad"),
                Map.of("path", dirB.toString(), "label", "")));

        List<Map<String, String>> dirs = AgentStore.getProjectDirs(agentId);
        assertEquals(2, dirs.size(), "nonexistent dir dropped");
        assertEquals(dirA.toString(), dirs.get(0).get("path"));
        assertEquals("A", dirs.get(0).get("label"));
        assertEquals(dirB.toString(), dirs.get(1).get("path"));
        // Primary mirror
        assertEquals(dirA.toString(), AgentStore.getProjectDir(agentId));
    }

    @Test
    void setProjectDirPromotesIntoDefaults() throws IOException {
        String agentId = "default";
        Path extra = Files.createDirectories(dirB.resolve("extra"));
        AgentStore.setProjectDirs(agentId, List.of(
                Map.of("path", dirA.toString()),
                Map.of("path", dirB.toString())));

        AgentStore.setProjectDir(agentId, extra.toString());

        List<Map<String, String>> dirs = AgentStore.getProjectDirs(agentId);
        assertEquals(3, dirs.size());
        assertEquals(extra.toString(), dirs.get(0).get("path"), "promoted to primary");
        assertEquals(dirA.toString(), dirs.get(1).get("path"), "existing order kept");
        assertEquals(extra.toString(), AgentStore.getProjectDir(agentId));
    }

    @Test
    void clearDirsAlsoClearsPrimary() {
        String agentId = "default";
        AgentStore.setProjectDirs(agentId, List.of(Map.of("path", dirA.toString())));
        AgentStore.setProjectDirs(agentId, List.of());

        assertTrue(AgentStore.getProjectDirs(agentId).isEmpty());
        assertNull(AgentStore.getProjectDir(agentId));
    }
}
