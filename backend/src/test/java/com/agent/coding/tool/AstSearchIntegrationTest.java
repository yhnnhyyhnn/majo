package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.agent.CodingModeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-binary integration for ast_search (ADR-0010): runs the actual
 * ast-grep CLI discovered on PATH against a temp workspace and checks the
 * model-facing JSON report. Skipped when ast-grep/sg is not installed —
 * the no-binary paths are covered by CodingToolsTest.
 */
class AstSearchIntegrationTest {

    @TempDir
    Path workspace;

    private final AstSearchTool tool = new AstSearchTool(enabledService());

    private static CodingModeService enabledService() {
        return new CodingModeService() {
            @Override
            public boolean isEnabled(String agentId) {
                return true;
            }
        };
    }

    @AfterEach
    void cleanUp() {
        WorkspaceContext.clear();
    }

    @Test
    void findsStructuralMatchesThroughRealBinary() throws Exception {
        Assumptions.assumeTrue(AstSearchTool.detectBinary() != null,
                "ast-grep not on PATH — skipping");
        Files.writeString(workspace.resolve("calc.ts"), """
                export function add(a: number, b: number) {
                  return a + b;
                }
                """);
        WorkspaceContext.set(workspace.toString());

        String out = tool.astSearch("function $F($$$A) { $$$B }", "typescript", "", 10, null);
        assertTrue(out.contains("\"matches\""), out);
        assertTrue(out.contains("calc.ts"), out);
        assertTrue(out.contains("\"line\":1"), "1-based line conversion: " + out);
        assertTrue(out.contains("add"), out);
    }

    @Test
    void noMatchesReportsCleanlyThroughRealBinary() {
        Assumptions.assumeTrue(AstSearchTool.detectBinary() != null,
                "ast-grep not on PATH — skipping");
        WorkspaceContext.set(workspace.toString());
        String out = tool.astSearch("function $F($$$A) { $$$B }", "typescript", "", 10, null);
        assertTrue(out.contains("No matches"), out);
    }

    // ── Kind mode (pattern-unreachable nodes, e.g. Java fields) ──────

    @Test
    void kindModeMatchesJavaFieldDeclarationsThroughRealBinary() throws Exception {
        Assumptions.assumeTrue(AstSearchTool.detectBinary() != null,
                "ast-grep not on PATH — skipping");
        Files.writeString(workspace.resolve("Field.java"), """
                public class Field {
                    private int x = 1;
                    int y = 2;
                    String name;
                }
                """);
        WorkspaceContext.set(workspace.toString());

        // Plain patterns cannot reach class fields (ast-grep limitation,
        // confirmed against the official catalog) — kind mode reaches all of
        // them regardless of modifiers.
        String patternProbe = tool.astSearch("int y = 2;", "java", "", 10, null);
        assertTrue(patternProbe.contains("No matches"), patternProbe);

        String out = tool.astSearch(null, "java", "", 10, "field_declaration");
        assertTrue(out.contains("\"matches\""), out);
        assertTrue(out.contains("int x = 1;"), out);
        assertTrue(out.contains("String name;"), out);
    }

    @Test
    void kindModeSupportsRegexTextFilter() throws Exception {
        Assumptions.assumeTrue(AstSearchTool.detectBinary() != null,
                "ast-grep not on PATH — skipping");
        Files.writeString(workspace.resolve("Field.java"), """
                public class Field {
                    private int x = 1;
                    String name;
                }
                """);
        WorkspaceContext.set(workspace.toString());

        // In kind mode the pattern acts as a node-text regex filter.
        String out = tool.astSearch("int", "java", "", 10, "field_declaration");
        assertTrue(out.contains("\"matches\""), out);
        assertTrue(out.contains("int x = 1;"), out);
        assertTrue(!out.contains("String name;"), out);
    }

    @Test
    void invalidKindIsRejectedWithoutSpawningAProcess() {
        WorkspaceContext.set(workspace.toString());
        String out = tool.astSearch(null, "java", "", 10, "bad kind!");
        assertTrue(out.contains("kind 仅允许"), out);
        String empty = tool.astSearch(null, "java", "", 10, null);
        assertTrue(empty.contains("至少一项必填"), empty);
    }
}
