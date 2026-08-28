package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

@Component
public class AppendFileTool {
    @Tool(name = "append_file", description = "追加内容到文件末尾，文件不存在则创建")
    public String appendFile(
        @ToolParam(name = "path", description = "文件路径") String path,
        @ToolParam(name = "content", description = "要追加的内容") String content
    ) throws IOException {
        var p = WorkspaceContext.get().resolve(path).normalize();
        var parent = p.getParent();
        if (parent != null) Files.createDirectories(parent);
        boolean existed = Files.exists(p);
        Files.writeString(p, content == null ? "" : content,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return (existed ? "[已追加] " : "[已创建] ") + path + " (" + Files.size(p) + " bytes)";
    }
}
