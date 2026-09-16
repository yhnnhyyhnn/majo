package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.agent.CodingModeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the ADR-0010 coding tools: ast-grep JSON rendering with
 * 0-based→1-based conversion and limits, the coding-mode call-time gate,
 * LSP JSON-RPC frame parsing, and language discovery.
 */
class CodingToolsTest {

    @TempDir
    Path workspace;

    private static CodingModeService toggle(boolean enabled) {
        return new CodingModeService() {
            @Override
            public boolean isEnabled(String agentId) {
                return enabled;
            }
        };
    }

    @AfterEach
    void cleanWorkspaceContext() {
        WorkspaceContext.clear();
    }

    // ── ast_search ───────────────────────────────────────────────────

    @Test
    void astSearchDisabledReturnsReadableGateReply() {
        AstSearchTool tool = new AstSearchTool(toggle(false));
        WorkspaceContext.set(workspace.toString());
        String out = tool.astSearch("def $F(): $$$B", "python", "", null);
        assertTrue(out.contains("Coding Mode 未启用"), out);
    }

    @Test
    void astSearchRequiresPatternAndLanguage() {
        AstSearchTool tool = new AstSearchTool(toggle(true));
        WorkspaceContext.set(workspace.toString());
        assertTrue(tool.astSearch(" ", "python", "", null).contains("必填"));
        assertTrue(tool.astSearch("def $F()", " ", "", null).contains("必填"));
    }

    @Test
    void astSearchMissingBinaryGivesInstallHint() {
        AstSearchTool tool = new AstSearchTool(toggle(true));
        WorkspaceContext.set(workspace.toString());
        String out = tool.astSearch("def $F(): $$$B", "python", "", null);
        if (AstSearchTool.detectBinary() == null) {
            assertTrue(out.contains("ast-grep 未安装"), out);
            assertTrue(out.contains("pip install ast-grep-cli"), out);
        } else {
            // Binary present on this machine — the run itself must succeed
            // (no matches in an empty workspace) rather than error.
            assertTrue(out.contains("matches") || out.contains("No matches"), out);
        }
    }

    @Test
    void renderConvertsZeroBasedToOneBasedAndRespectsLimit() throws Exception {
        AstSearchTool tool = new AstSearchTool(toggle(true));
        // ast-grep compact JSON: rows carry 0-based line/column.
        String stdout = """
                [{"file":"a\\\\b.py","range":{"start":{"line":0,"column":2},"end":{"line":0,"column":9}},"text":"def foo():"},
                 {"file":"c.py","range":{"start":{"line":9,"column":0},"end":{"line":9,"column":9}},"text":"def bar():"}]
                """;
        String out = tool.render(stdout, 200);
        assertTrue(out.contains("\"file\":\"a/b.py\""), out);
        assertTrue(out.contains("\"line\":1"), out);
        assertTrue(out.contains("\"end_line\":10"), out);
        assertFalse(out.contains("\"truncated\": true"));

        String limited = tool.render(stdout, 1);
        assertTrue(limited.contains("\"truncated\": true"), limited);
        assertTrue(limited.contains("a/b.py"));
        assertFalse(limited.contains("c.py"));

        assertEquals("No matches for pattern in the given language.", tool.render("", 200));
        assertEquals("No matches for pattern in the given language.", tool.render("[]", 200));
    }

    // ── lsp ──────────────────────────────────────────────────────────

    @Test
    void lspDisabledReturnsReadableGateReply() {
        LspTool tool = new LspTool(toggle(false));
        WorkspaceContext.set(workspace.toString());
        String out = tool.lsp("goToDefinition", "A.java", 1, 1, null);
        assertTrue(out.contains("Coding Mode 未启用"), out);
    }

    @Test
    void lspUnknownLanguageListsAdvertisedLanguages() {
        LspTool tool = new LspTool(toggle(true));
        WorkspaceContext.set(workspace.toString());
        String out = tool.lsp("hover", "readme.unknownext", 1, 1, null);
        assertTrue(out.contains("无法从文件推断语言"), out);
    }

    @Test
    void lspOperationRequired() {
        LspTool tool = new LspTool(toggle(true));
        WorkspaceContext.set(workspace.toString());
        assertTrue(tool.lsp(" ", "A.java", 1, 1, null).contains("operation 必填"));
    }

    @Test
    void languageForMapsExtensions() {
        assertEquals("typescript", LspClient.languageFor("App.tsx"));
        assertEquals("javascript", LspClient.languageFor("index.js"));
        assertEquals("python", LspClient.languageFor("main.py"));
        assertNull(LspClient.languageFor("notes.md"));
    }

    @Test
    void readFrameHeaderParsesLspFraming() throws Exception {
        String frame = "Content-Length: 13\r\nContent-Type: x\r\n\r\n";
        var in = new ByteArrayInputStream(frame.getBytes(StandardCharsets.US_ASCII));
        String header = LspClient.readFrameHeader(in);
        assertEquals(13, LspClient.parseContentLength(header));

        // EOF without terminator → null.
        var truncated = new ByteArrayInputStream("Content-Length: 1\r\n".getBytes(StandardCharsets.US_ASCII));
        assertNull(LspClient.readFrameHeader(truncated));

        assertEquals(-1, LspClient.parseContentLength("X-Other: 5\r\n\r\n"));
    }

    @Test
    void availableLanguagesOnlyIncludesDiscoverableServers() {
        List<String> langs = LspClient.availableLanguages();
        for (String lang : langs) {
            assertTrue(LspClient.discover(lang) != null);
        }
        // Every advertised language comes from the well-known spec set.
        for (String lang : langs) {
            assertTrue(lang.equals("typescript") || lang.equals("javascript")
                    || lang.equals("python"), lang);
        }
    }

    // ── Files creation helper silence (workspace touched) ───────────

    @Test
    void workspaceWriteSanity() throws Exception {
        Files.writeString(workspace.resolve("A.java"), "class A {}");
        assertTrue(Files.exists(workspace.resolve("A.java")));
    }
}
