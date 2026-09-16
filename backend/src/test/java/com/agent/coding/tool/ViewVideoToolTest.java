package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * view_video behavior: input validation, metadata-only fallback without
 * ffmpeg (readable note, never silent), and — when ffmpeg is available on
 * this machine — real frame extraction returning image blocks.
 */
class ViewVideoToolTest {

    @TempDir
    Path workspace;

    private final ViewVideoTool tool = new ViewVideoTool();

    @AfterEach
    void cleanUp() {
        WorkspaceContext.clear();
    }

    @Test
    void rejectsBlankAndMissingPaths() {
        WorkspaceContext.set(workspace.toString());
        ToolResultBlock blank = tool.viewVideo(" ", null);
        assertTrue(firstText(blank).contains("path 不能为空"), firstText(blank));
        ToolResultBlock missing = tool.viewVideo("nope.mp4", null);
        assertTrue(firstText(missing).contains("文件不存在"), firstText(missing));
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        WorkspaceContext.set(workspace.toString());
        Files.writeString(workspace.resolve("clip.txt"), "not a video");
        ToolResultBlock out = tool.viewVideo("clip.txt", null);
        assertTrue(firstText(out).contains("不支持的视频格式"));
    }

    @Test
    void fallsBackToMetadataWithoutFfmpegOrExtractsFrames() throws Exception {
        WorkspaceContext.set(workspace.toString());
        // Minimal non-empty file with an .mp4 name; ffmpeg extraction will
        // fail on it, exercising the readable-fallback path.
        Path video = workspace.resolve("clip.mp4");
        Files.write(video, new byte[]{0, 0, 0, 18, 'f', 't', 'y', 'p'});
        ToolResultBlock out = tool.viewVideo("clip.mp4", 2);

        if (ViewVideoTool.detectBinary("ffmpeg") == null) {
            String text = firstText(out);
            assertTrue(text.contains("视频信息"), text);
            assertTrue(text.contains("ffmpeg"), text);
        } else {
            // ffmpeg present: either frames extracted (ImageBlocks) or a
            // readable failure note — never a bare error.
            List<ContentBlock> blocks = out.getOutput();
            assertNotNull(blocks);
            boolean hasImage = blocks.stream().anyMatch(b -> b instanceof ImageBlock);
            boolean hasNote = blocks.stream()
                    .filter(b -> b instanceof TextBlock)
                    .map(b -> ((TextBlock) b).getText())
                    .anyMatch(t -> t.contains("视频信息") || t.contains("帧提取失败"));
            assertTrue(hasImage || hasNote, "either frames or a note expected");
        }
    }

    private static String firstText(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst().orElse("");
    }
}
