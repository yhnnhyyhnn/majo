package com.agent.coding.tool;

import com.agent.coding.skill.SkillStore;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Capture the desktop screen and load it into the LLM context (and save a
 * copy to the uploads dir for the user). Requires a graphical session
 * (java.awt.Robot); returns a clear error on headless environments.
 */
@Component
public class DesktopScreenshotTool {

    private static final int MAX_EDGE = 1568;

    @Tool(name = "desktop_screenshot", description = "截取桌面屏幕并加载到上下文中供视觉分析")
    public ToolResultBlock desktopScreenshot() {
        try {
            Robot robot = new Robot();
            Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage img = robot.createScreenCapture(screen);

            if (img.getWidth() > MAX_EDGE || img.getHeight() > MAX_EDGE) {
                double scale = (double) MAX_EDGE / Math.max(img.getWidth(), img.getHeight());
                int w = Math.max(1, (int) (img.getWidth() * scale));
                int h = Math.max(1, (int) (img.getHeight() * scale));
                BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.drawImage(img, 0, 0, w, h, null);
                g.dispose();
                img = scaled;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            byte[] bytes = baos.toByteArray();

            String name = "screenshot-" + Instant.now().toEpochMilli() + ".png";
            Path dir = SkillStore.WORKING_DIR.resolve("uploads");
            Files.createDirectories(dir);
            Path out = dir.resolve(name);
            Files.write(out, bytes);

            String b64 = Base64.getEncoder().encodeToString(bytes);
            TextBlock text = TextBlock.builder()
                    .text("桌面截图已保存并加载到上下文: " + name + " (" + bytes.length + " bytes)\n"
                            + "预览: /api/files/preview/" + name)
                    .build();
            ImageBlock image = ImageBlock.builder()
                    .source(new Base64Source("image/png", b64))
                    .maxPixels(MAX_EDGE * MAX_EDGE)
                    .build();
            return ToolResultBlock.of(List.of(text, image));
        } catch (java.awt.AWTException e) {
            return ToolResultBlock.text("错误: 无法访问图形环境(Headless 或权限不足): " + e.getMessage());
        } catch (Exception e) {
            return ToolResultBlock.text("错误: " + e.getMessage());
        }
    }
}
