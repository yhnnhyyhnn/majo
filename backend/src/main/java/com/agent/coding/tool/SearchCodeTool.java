package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

@Component
public class SearchCodeTool {
    @Tool(name = "search_code", description = "在代码中搜索关键词或正则")
    public String searchCode(
        @ToolParam(name = "pattern", description = "搜索关键词或正则") String pattern,
        @ToolParam(name = "filePattern", description = "文件名过滤(可选,如 *.java)") String filePattern,
        @ToolParam(name = "maxResults", description = "最大结果数(可选,默认20)") Integer maxResults
    ) throws IOException {
        var regex = Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
        int limit = maxResults != null ? Math.min(maxResults, 50) : 20;
        var results = new ArrayList<String>();
        var root = WorkspaceContext.get();
        search(root, root, regex, filePattern, limit, results);
        if (results.isEmpty()) return "未找到 '" + pattern + "'";
        return "找到 " + results.size() + " 处:\n" + String.join("\n", results);
    }

    private void search(Path root, Path dir, Pattern p, String fp, int max, ArrayList<String> r) {
        try (var s = Files.newDirectoryStream(dir)) {
            for (Path e : s) {
                if (r.size() >= max) return;
                String n = e.getFileName().toString();
                if (n.startsWith(".") || n.equals("target") || n.equals("node_modules") || n.equals(".git")) continue;
                if (Files.isDirectory(e)) { search(root, e, p, fp, max, r); continue; }
                if (fp != null && !n.toLowerCase().endsWith(fp.replace("*", "").toLowerCase())) continue;
                try {
                    var lines = Files.readAllLines(e);
                    for (int i = 0; i < lines.size() && r.size() < max; i++)
                        if (p.matcher(lines.get(i)).find())
                            r.add(root.relativize(e) + ":" + (i + 1) + "  " + lines.get(i).trim());
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }
}
