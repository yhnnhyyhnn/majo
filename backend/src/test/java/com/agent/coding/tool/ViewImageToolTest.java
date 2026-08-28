package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ViewImageTool 单元测试（纯 JUnit，临时工作区）。
 */
class ViewImageToolTest {

    @TempDir
    Path workspace;

    private final ViewImageTool tool = new ViewImageTool();

    @BeforeEach
    void setUp() {
        WorkspaceContext.set(workspace.toString());
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    private Path writePng(String name, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, (x * 255 / w) << 16 | (y * 255 / h) << 8 | 0xFF);
            }
        }
        File f = workspace.resolve(name).toFile();
        ImageIO.write(img, "png", f);
        return f.toPath();
    }

    @Test
    void returnsImageBlockForPng() throws Exception {
        writePng("photo.png", 64, 48);
        ToolResultBlock result = tool.viewImage("photo.png");
        List<ContentBlock> out = result.getOutput();
        assertNotNull(out);
        assertEquals(2, out.size(), "应返回文本 + 图片两个块");
        assertInstanceOf(TextBlock.class, out.get(0));
        ImageBlock img = assertInstanceOf(ImageBlock.class, out.get(1));
        assertNotNull(img.getSource(), "图片 source 不应为空");
        assertTrue(img.getSource().getClass().getSimpleName().contains("Base64"), "应为 base64 source");
    }

    @Test
    void downscalesHugeImages() throws Exception {
        writePng("big.png", 3000, 2000);
        ToolResultBlock result = tool.viewImage("big.png");
        ImageBlock img = (ImageBlock) result.getOutput().get(1);
        // 大图应被降采样：data 长度应远小于原始 3000x2000 的 base64 规模
        io.agentscope.core.message.Base64Source src =
                (io.agentscope.core.message.Base64Source) img.getSource();
        assertTrue(src.getData().length() > 100, "应有数据");
        assertTrue(src.getData().length() < 2_000_000, "过大图应被降采样");
    }

    @Test
    void missingFileReturnsErrorText() {
        ToolResultBlock result = tool.viewImage("nope.png");
        TextBlock tb = (TextBlock) result.getOutput().get(0);
        assertTrue(tb.getText().contains("不存在"), tb.getText());
    }

    @Test
    void unsupportedExtensionReturnsError() throws Exception {
        Files.writeString(workspace.resolve("doc.txt"), "hello");
        ToolResultBlock result = tool.viewImage("doc.txt");
        TextBlock tb = (TextBlock) result.getOutput().get(0);
        assertTrue(tb.getText().contains("不支持"), tb.getText());
    }

    @Test
    void blankPathReturnsError() {
        ToolResultBlock result = tool.viewImage("  ");
        TextBlock tb = (TextBlock) result.getOutput().get(0);
        assertTrue(tb.getText().contains("path"), tb.getText());
    }
}
