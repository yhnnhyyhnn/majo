package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ReadFileTool {
    @Tool(name = "read_file", description = "读取文件内容，支持 offset/limit 分段")
    public String readFile(
        @ToolParam(name = "path", description = "文件路径") String path,
        @ToolParam(name = "offset", description = "起始行号(可选)") Integer offset,
        @ToolParam(name = "limit", description = "最大行数(可选)") Integer limit
    ) throws IOException {
        var p = WorkspaceContext.get().resolve(path).normalize();
        if (!Files.exists(p)) return "文件不存在: " + path;
        if (Files.size(p) > 100_000) return "文件过大 (" + Files.size(p) + " bytes)";
        var lines = Files.readAllLines(p);
        int start = offset != null ? offset : 0;
        int end = limit != null ? Math.min(start + limit, lines.size()) : lines.size();
        if (start >= lines.size()) return "offset 超出文件行数";
        var sb = new StringBuilder();
        for (int i = start; i < end; i++)
            sb.append(String.format("%6d| %s%n", i + 1, lines.get(i)));
        return sb.toString();
    }
}
