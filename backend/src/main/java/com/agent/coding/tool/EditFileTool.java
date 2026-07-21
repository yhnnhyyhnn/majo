package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class EditFileTool {
    @Tool(name = "edit_file", description = "精确文本替换(oldString→newString)")
    public String editFile(
        @ToolParam(name = "path", description = "文件路径") String path,
        @ToolParam(name = "oldString", description = "要替换的原文") String oldString,
        @ToolParam(name = "newString", description = "新文本") String newString
    ) throws IOException {
        var p = WorkspaceContext.get().resolve(path).normalize();
        if (!Files.exists(p)) return "文件不存在: " + path;
        var content = Files.readString(p);
        int count = 0, idx = 0;
        while ((idx = content.indexOf(oldString, idx)) != -1) { count++; idx += oldString.length(); }
        if (count == 0) return "未找到 oldString";
        if (count > 1) return "oldString 匹配到 " + count + " 处，需唯一";
        Files.writeString(p, content.replace(oldString, newString));
        return "[已修改] " + path;
    }
}
