package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Component
public class ListDirectoryTool {
    @Tool(name = "list_directory", description = "列出目录内容")
    public String listDirectory(
        @ToolParam(name = "path", description = "目录路径(可选)") String path
    ) throws IOException {
        Path dir = path != null ? WorkspaceContext.get().resolve(path).normalize() : WorkspaceContext.get();
        if (!Files.isDirectory(dir)) return "不是目录: " + dir;
        var sb = new StringBuilder(dir.getFileName() + "/:\n");
        try (var s = Files.list(dir)) {
            s.filter(p -> !p.getFileName().toString().startsWith(".") || p.getFileName().toString().equals(".gitignore"))
             .sorted(Comparator.comparing(p -> (Files.isDirectory(p) ? "0_" : "1_") + p.getFileName()))
             .limit(80).forEach(p -> sb.append("  ").append(p.getFileName()).append(Files.isDirectory(p) ? "/\n" : "\n"));
        }
        return sb.toString();
    }
}
