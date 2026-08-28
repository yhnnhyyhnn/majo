package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Load an image from the workspace into the LLM context for visual analysis.
 * The tool returns an {@link ImageBlock} inside the result; {@link
 * MediaPromotionHook} then promotes it into the conversation so the next
 * model call is multimodal.
 *
 * <p>Images are downscaled to {@code MAX_EDGE} pixels to stay within
 * provider vision limits.
 */
@Component
public class ViewImageTool {

    private static final Map<String, String> MIME = Map.of(
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".gif", "image/gif",
            ".webp", "image/webp",
            ".bmp", "image/bmp");

    private static final long MAX_BYTES = 15L * 1024 * 1024;
    private static final int MAX_EDGE = 1568;

    @Tool(name = "view_image", description = "加载工作区中的图片到上下文中供视觉分析")
    public ToolResultBlock viewImage(
        @ToolParam(name = "path", description = "图片路径") String path
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
                return ToolResultBlock.text("错误: 图片过大 (" + size + " bytes)，上限 15MB");
            }
            String name = path.toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? "" : name.substring(dot);
            String mime = MIME.get(ext);
            if (mime == null) {
                return ToolResultBlock.text("错误: 不支持的图片格式: " + (ext.isEmpty() ? path : ext));
            }

            byte[] bytes = Files.readAllBytes(p);
            BufferedImage img = ImageIO.read(p.toFile());
            if (img != null && (img.getWidth() > MAX_EDGE || img.getHeight() > MAX_EDGE)) {
                double scale = (double) MAX_EDGE / Math.max(img.getWidth(), img.getHeight());
                int w = Math.max(1, (int) (img.getWidth() * scale));
                int h = Math.max(1, (int) (img.getHeight() * scale));
                BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.drawImage(img, 0, 0, w, h, null);
                g.dispose();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(scaled, "png", baos);
                bytes = baos.toByteArray();
                mime = "image/png";
            }

            String b64 = Base64.getEncoder().encodeToString(bytes);
            TextBlock text = TextBlock.builder()
                    .text("图像已加载到上下文: " + path + " (" + bytes.length + " bytes, " + mime + ")")
                    .build();
            ImageBlock image = ImageBlock.builder()
                    .source(new Base64Source(mime, b64))
                    .maxPixels(MAX_EDGE * MAX_EDGE)
                    .build();
            return ToolResultBlock.of(List.of(text, image));
        } catch (Exception e) {
            return ToolResultBlock.text("错误: " + e.getMessage());
        }
    }
}
