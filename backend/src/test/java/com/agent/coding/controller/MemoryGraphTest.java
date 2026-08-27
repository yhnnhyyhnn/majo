package com.agent.coding.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MemoryGraphTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nodeById(Map<String, Object> graph, String id) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        return nodes.stream()
                .filter(n -> id.equals(n.get("id")))
                .findFirst()
                .orElse(null);
    }

    @Test
    void emptyDirectoryReturnsEmptyGraph() throws Exception {
        Path dir = Files.createTempDirectory("majo-graph-empty");
        Map<String, Object> graph = AgentsController.buildMemoryGraph(dir);
        assertEquals(1, graph.get("version"));
        assertTrue(((List<?>) graph.get("nodes")).isEmpty());
        assertTrue(((List<?>) graph.get("edges")).isEmpty());
    }

    @Test
    void wikilinksBecomeEdgesWithCategoryRoots() throws Exception {
        Path dir = Files.createTempDirectory("majo-graph-wiki");
        Path digestWiki = dir.resolve("digest").resolve("wiki");
        Files.createDirectories(digestWiki);
        Files.writeString(dir.resolve("MEMORY.md"), "see [[digest/wiki/architecture]]\n");
        Files.writeString(digestWiki.resolve("architecture.md"),
                "# Architecture\nlinks to [[database]]\n");

        Map<String, Object> graph = AgentsController.buildMemoryGraph(dir);
        assertEquals(1, graph.get("version"));

        // Nodes: MEMORY.md + digest/wiki/architecture.md + category root wiki
        assertTrue(nodeById(graph, "file:memory.md") != null, "memory file node");
        assertTrue(nodeById(graph,
                "file:digest/wiki/architecture.md") != null, "architecture file node");
        assertTrue(nodeById(graph, "root:digest/wiki") != null, "category root node");
        // Unresolved [[database]] target becomes a virtual node
        assertTrue(nodeById(graph, "unresolved:database") != null, "unresolved node");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");
        assertEquals(2, edges.size(), "two wikilink edges");

        boolean memoryToArchitecture = edges.stream().anyMatch(e ->
                "file:memory.md".equals(e.get("source"))
                        && "file:digest/wiki/architecture.md".equals(e.get("target")));
        boolean architectureToDatabase = edges.stream().anyMatch(e ->
                "file:digest/wiki/architecture.md".equals(e.get("source"))
                        && "unresolved:database".equals(e.get("target")));
        assertTrue(memoryToArchitecture, "edge MEMORY.md -> architecture");
        assertTrue(architectureToDatabase, "edge architecture -> database");
    }
}
