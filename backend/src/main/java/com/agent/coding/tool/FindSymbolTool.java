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
public class FindSymbolTool {
    @Tool(name = "find_symbol", description = "查找类/函数/变量定义位置")
    public String findSymbol(
        @ToolParam(name = "symbolName", description = "符号名称") String symbolName,
        @ToolParam(name = "language", description = "语言后缀(可选,如 .java)") String language
    ) throws IOException {
        var root = WorkspaceContext.get();
        var results = new ArrayList<String>();
        var javaPat = Pattern.compile("(class|interface|enum|record)\\s+(\\w+)");
        findInDir(root, root, symbolName, language, javaPat, results, 30);
        if (results.isEmpty()) return "未找到符号 '" + symbolName + "'";
        return "找到 " + results.size() + " 处:\n" + String.join("\n", results);
    }

    private void findInDir(Path root, Path dir, String name, String lang, Pattern pat, ArrayList<String> r, int max) {
        try (var s = Files.newDirectoryStream(dir)) {
            for (Path e : s) {
                if (r.size() >= max) return;
                String n = e.getFileName().toString();
                if (n.startsWith(".") || n.equals("target") || n.equals("node_modules") || n.equals(".git")) continue;
                if (Files.isDirectory(e)) { findInDir(root, e, name, lang, pat, r, max); continue; }
                if (lang != null && !n.endsWith(lang)) continue;
                try {
                    var lines = Files.readAllLines(e);
                    for (int i = 0; i < lines.size() && r.size() < max; i++) {
                        var m = pat.matcher(lines.get(i));
                        while (m.find() && r.size() < max)
                            if (name.equals(m.group(2)))
                                r.add(root.relativize(e) + ":" + (i + 1) + "  " + lines.get(i).trim());
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }
}
