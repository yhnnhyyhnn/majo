package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class WriteFileTool {
    @Tool(name = "write_file", description = "创建或覆盖文件")
    public String writeFile(
        @ToolParam(name = "path", description = "文件路径") String path,
        @ToolParam(name = "content", description = "文件内容") String content
    ) throws IOException {
        var p = WorkspaceContext.get().resolve(path).normalize();
        var parent = p.getParent();
        if (parent != null) Files.createDirectories(parent);
        boolean existed = Files.exists(p);
        Files.writeString(p, content);
        return (existed ? "[已更新] " : "[已创建] ") + path + " (" + Files.size(p) + " bytes)";
    }
}
