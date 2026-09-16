package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.agent.CodingModeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Structural code search backed by the ast-grep CLI (ADR-0010), ported from
 * QwenPaw's {@code ast_tool.py}. Strictly read-only: the model rewrites
 * matches via read + edit_file. The binary is probed on PATH
 * ({@code ast-grep} or {@code sg}); when absent the tool replies with an
 * install hint instead of failing opaquely. Gated on Coding Mode at call
 * time (shared-Toolkit deviation from QwenPaw's registry filtering).
 */
@Component
public class AstSearchTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_OUTPUT_CHARS = 80_000;
    private static final int CLI_TIMEOUT_SECONDS = 30;

    private final CodingModeService codingModeService;

    public AstSearchTool(CodingModeService codingModeService) {
        this.codingModeService = codingModeService;
    }

    /** Locate the ast-grep binary on PATH, or null. */
    static String detectBinary() {
        for (String name : List.of(System.getProperty("os.name", "").toLowerCase().contains("win")
                ? new String[]{"ast-grep.exe", "sg.exe", "ast-grep", "sg"}
                : new String[]{"ast-grep", "sg"})) {
            for (String dir : System.getenv("PATH") == null
                    ? new String[0] : System.getenv("PATH").split("[;]")) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir.strip(), name);
                // On Windows also accept extensionless names resolved by the shell.
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    @Tool(name = "ast_search",
            description = "结构化代码搜索（ast-grep 语法）: $NAME 匹配单节点, $$$NAME 匹配多节点, "
                    + "如 \"def $FUNC($$$ARGS): $$$BODY\"。只读; language 必填(java/python/typescript/...")
    public String astSearch(
        @ToolParam(name = "pattern", description = "ast-grep 模式") String pattern,
        @ToolParam(name = "language", description = "语言(java/python/typescript/javascript/go/rust/c/cpp...)")
        String language,
        @ToolParam(name = "path", description = "限定目录或文件(可选,相对工作区,空=全项目)") String path,
        @ToolParam(name = "max_matches", description = "最大匹配数(可选,默认200,上限1000)") Integer maxMatches
    ) {
        if (!codingModeService.isEnabled(agentIdOf())) {
            return CodingModeService.disabledReply();
        }
        if (pattern == null || pattern.isBlank() || language == null || language.isBlank()) {
            return "错误: pattern 与 language 均为必填。";
        }
        int limit = maxMatches == null ? 200 : Math.max(1, Math.min(maxMatches, 1000));

        String binary = detectBinary();
        if (binary == null) {
            return "ast-grep 未安装。请安装后重试: `pip install ast-grep-cli`"
                    + "（或系统包管理器安装 ast-grep）, 二进制名为 ast-grep 或 sg。";
        }

        Path workspace = WorkspaceContext.get();
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.add("run");
        cmd.add("--pattern");
        cmd.add(pattern);
        cmd.add("--lang");
        cmd.add(language.strip());
        cmd.add("--json=compact");
        if (path != null && !path.isBlank()) {
            cmd.add(workspace.resolve(path.strip()).normalize().toString());
        }

        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(false);
            proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            String stderr = new String(proc.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!proc.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return "ast-grep 超时(" + CLI_TIMEOUT_SECONDS + "s): 请缩小 path 范围或使用更具体的 pattern。";
            }
            int code = proc.exitValue();
            if (code != 0) {
                // ast-grep exits non-zero on bad pattern/language; surface stderr.
                String reason = stderr.isBlank() ? ("exit code " + code) : stderr.strip();
                return "ast-grep 执行失败: " + truncate(reason);
            }
            return render(stdout, limit);
        } catch (IOException e) {
            return "ast-grep 执行失败: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ast-grep 执行被中断。";
        } finally {
            if (proc != null) {
                proc.destroyForcibly();
            }
        }
    }

    /** Parse ast-grep compact JSON output into the model-facing report. */
    String render(String stdout, int limit) throws IOException {
        if (stdout.isBlank()) {
            return "No matches for pattern in the given language.";
        }
        JsonNode root = MAPPER.readTree(stdout);
        if (!root.isArray() || root.isEmpty()) {
            return "No matches for pattern in the given language.";
        }
        StringBuilder sb = new StringBuilder("{\"matches\": [");
        int count = 0;
        boolean truncated = false;
        for (JsonNode m : root) {
            if (count >= limit) {
                truncated = true;
                break;
            }
            if (count > 0) {
                sb.append(',');
            }
            sb.append("{\"file\":\"").append(esc(text(m, "file"))).append('"');
            // ast-grep JSON rows are 0-based; the tool contract is 1-based.
            sb.append(",\"line\":").append(m.path("range").path("start").path("line").asInt() + 1);
            sb.append(",\"column\":").append(m.path("range").path("start").path("column").asInt() + 1);
            sb.append(",\"end_line\":").append(m.path("range").path("end").path("line").asInt() + 1);
            String snip = m.path("text").asText("");
            if (snip.length() > 400) {
                snip = snip.substring(0, 400);
            }
            sb.append(",\"snippet\":\"").append(esc(snip)).append("\"}");
            count++;
            if (sb.length() > MAX_OUTPUT_CHARS) {
                truncated = true;
                break;
            }
        }
        sb.append("], \"truncated\": ").append(truncated).append("}");
        String out = sb.toString();
        if (out.length() > MAX_OUTPUT_CHARS) {
            out = out.substring(0, MAX_OUTPUT_CHARS) + "… (truncated)";
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").replace('\\', '/');
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    private static String truncate(String s) {
        return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
    }

    private static String agentIdOf() {
        // The workspace path ends with workspaces/{agent_id} by convention.
        Path ws = WorkspaceContext.get();
        return ws.getFileName() != null ? ws.getFileName().toString() : "default";
    }
}
