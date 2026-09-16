package com.agent.coding.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-server integration for the LSP client (ADR-0010): spawns the actual
 * typescript-language-server discovered on PATH, runs the initialize
 * handshake and a documentSymbol query. Skipped when no server is
 * installed — the no-server paths are covered by CodingToolsTest.
 */
class LspClientIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @AfterAll
    static void shutDownPool() {
        LspClient.shutdownAll();
    }

    @Test
    void documentSymbolAgainstRealTypescriptServer() throws Exception {
        Assumptions.assumeTrue(LspClient.discover("typescript") != null,
                "typescript-language-server not on PATH — skipping");

        Files.writeString(workspace.resolve("probe.ts"), """
                export interface Greeter {
                  name: string;
                }
                export function greet(g: Greeter): string {
                  return "hi " + g.name;
                }
                """);

        LspClient client = LspClient.get(workspace, "typescript");
        try {
            JsonNode result = client.operate("documentSymbol", "probe.ts", null, null, null);
            assertNotNull(result);
            assertTrue(result.isArray(), "documentSymbol returns an array: " + result);
            List<String> names = new java.util.ArrayList<>();
            result.forEach(s -> names.add(s.path("name").asText()));
            assertTrue(names.contains("Greeter"), "symbols: " + names);
            assertTrue(names.contains("greet"), "symbols: " + names);
        } finally {
            client.close();
        }
    }

    @Test
    void pooledClientIsReusedForSameWorkspaceLanguage() throws Exception {
        Assumptions.assumeTrue(LspClient.discover("typescript") != null,
                "typescript-language-server not on PATH — skipping");

        LspClient first = LspClient.get(workspace, "typescript");
        LspClient second = LspClient.get(workspace, "typescript");
        assertEquals(first, second, "same (workspace, language) must reuse the pooled client");
        first.close();
    }
}
