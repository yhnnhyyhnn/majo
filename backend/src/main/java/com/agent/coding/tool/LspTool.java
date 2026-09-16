package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.agent.CodingModeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * The {@code lsp} tool (ADR-0010) — definition/references/hover/impl/symbols
 * through a pooled language server, ported from QwenPaw's lsp_tool. All
 * failures return model-readable text with a fallback hint
 * (search_code / ast_search); line/character are 1-based.
 */
@Component
public class LspTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_OUTPUT_CHARS = 80_000;

    private final CodingModeService codingModeService;

    public LspTool(CodingModeService codingModeService) {
        this.codingModeService = codingModeService;
    }

    @Tool(name = "lsp",
            description = "代码智能(语言服务器): goToDefinition/findReferences/hover/goToImplementation"
                    + "(需 file_path+line+character, 1-based), documentSymbol(需 file_path), "
                    + "workspaceSymbol(需 query)。line/character 为 1-based。")
    public String lsp(
        @ToolParam(name = "operation", description = "操作: goToDefinition/findReferences/hover/"
                + "goToImplementation/documentSymbol/workspaceSymbol") String operation,
        @ToolParam(name = "file_path", description = "文件相对路径(position/documentSymbol 操作必填)")
        String filePath,
        @ToolParam(name = "line", description = "行号(1-based, position 操作必填)") Integer line,
        @ToolParam(name = "character", description = "列号(1-based, position 操作必填)") Integer character,
        @ToolParam(name = "query", description = "workspaceSymbol 查询词") String query
    ) {
        if (!codingModeService.isEnabled(agentIdOf())) {
            return CodingModeService.disabledReply();
        }
        if (operation == null || operation.isBlank()) {
            return "错误: operation 必填。";
        }
        Path workspace = WorkspaceContext.get();
        String fileName = filePath == null || filePath.isBlank() ? "" : Path.of(filePath.strip()).getFileName().toString();
        String language = fileName.isEmpty() ? null : LspClient.languageFor(fileName);
        if (language == null) {
            return "无法从文件推断语言或该操作不需要文件。"
                    + (LspClient.availableLanguages().isEmpty()
                    ? "当前没有可用的语言服务器（可安装 typescript-language-server / pyright / pylsp）。"
                    : "支持的语言: " + String.join(", ", LspClient.availableLanguages())
                    + "。其他语言请回退 search_code / ast_search。");
        }
        if (LspClient.discover(language) == null) {
            return language + " 没有可用的语言服务器（可安装 typescript-language-server / pyright / pylsp），"
                    + "请回退 search_code / ast_search。";
        }
        try {
            LspClient client = LspClient.get(workspace, language);
            JsonNode result = client.operate(operation.strip(), filePath, line, character, query);
            if (result == null || result.isNull()) {
                return "No result for " + operation;
            }
            String body = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            if (body.length() > MAX_OUTPUT_CHARS) {
                body = body.substring(0, MAX_OUTPUT_CHARS) + "\n… (truncated)";
            }
            return body;
        } catch (LspClient.LspException e) {
            return "Error: LSP " + operation + " 失败 — " + e.getMessage()
                    + "（可回退 search_code / ast_search）";
        } catch (Exception e) {
            return "Error: LSP " + operation + " failed — " + e.getMessage();
        }
    }

    /** Languages advertised in errors/help (for tests and diagnostics). */
    public static List<String> advertisedLanguages() {
        return LspClient.availableLanguages();
    }

    private static String agentIdOf() {
        Path ws = WorkspaceContext.get();
        return ws.getFileName() != null ? ws.getFileName().toString() : "default";
    }
}
