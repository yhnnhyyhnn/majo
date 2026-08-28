package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Load video metadata into context. Note: the OpenAI-compatible formatter
 * does not carry video content in tool results, so this returns a text
 * reference only (frame extraction is not implemented).
 */
@Component
public class ViewVideoTool {

    private static final long MAX_BYTES = 500L * 1024 * 1024;

    @Tool(name = "view_video", description = "查看工作区中的视频文件信息(帧提取暂不支持)")
    public ToolResultBlock viewVideo(
        @ToolParam(name = "path", description = "视频路径") String path
    ) {
        if (path == null || path.isBlank()) {
            return ToolResultBlock.text("错误: path 不能为空");
        }
        try {
            Path p = WorkspaceContext.get().resolve(path).normalize();
            if (!Files.isRegularFile(p)) {
                return ToolResultBlock.text("错误: 文件不存在: " + path);
            }
            long size = Files.size(p);
            if (size > MAX_BYTES) {
                return ToolResultBlock.text("错误: 视频过大 (" + size + " bytes)，上限 500MB");
            }
            String name = p.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean video = lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")
                    || lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".flv");
            if (!video) {
                return ToolResultBlock.text("错误: 不支持的视频格式: " + name);
            }
            return ToolResultBlock.of(TextBlock.builder()
                    .text("视频信息: " + path + " (" + size + " bytes)\n"
                            + "注意: 当前模型通道不支持视频帧直接进入上下文；如需分析视频内容，"
                            + "请先使用工具提取信息或由用户提供关键帧。")
                    .build());
        } catch (Exception e) {
            return ToolResultBlock.text("错误: " + e.getMessage());
        }
    }
}
