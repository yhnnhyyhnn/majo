package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Load video keyframes into the LLM context for visual analysis (ADR-0009's
 * predecessor gap closed; aligns QwenPaw's view_media). When ffmpeg is on
 * PATH, extracts up to {@value #DEFAULT_FRAMES} evenly-spaced frames via
 * {@code ffmpeg -ss <t> -i ... -frames:v 1} and returns them as ImageBlocks
 * (MediaPromotionHook promotes them like view_image). Without ffmpeg — or on
 * any extraction failure — falls back to metadata-only with a readable note,
 * never a silent degradation.
 */
@Component
public class ViewVideoTool {

    private static final Logger log = LoggerFactory.getLogger(ViewVideoTool.class);

    private static final long MAX_BYTES = 500L * 1024 * 1024;
    private static final int DEFAULT_FRAMES = 4;
    private static final int MAX_FRAMES = 10;
    private static final int MAX_EDGE = 1024;
    private static final int FFPROBE_TIMEOUT_SECONDS = 20;
    private static final int FRAME_TIMEOUT_SECONDS = 30;

    @Tool(name = "view_video", description = "提取工作区中视频的关键帧并加载到上下文供视觉分析"
            + "(需要系统安装 ffmpeg; 未安装时仅返回视频元信息)")
    public ToolResultBlock viewVideo(
        @ToolParam(name = "path", description = "视频路径") String path,
        @ToolParam(name = "frames", description = "提取帧数(可选,默认4,上限10)") Integer frames
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

            int frameCount = frames == null ? DEFAULT_FRAMES : Math.max(1, Math.min(frames, MAX_FRAMES));
            String ffmpeg = detectBinary("ffmpeg");
            if (ffmpeg == null) {
                return metadataOnly(path, size,
                        "未检测到 ffmpeg，无法提取帧。安装 ffmpeg 后可自动提取关键帧进行视觉分析。");
            }

            double duration = probeDurationSeconds(ffmpeg.replace("ffmpeg", "ffprobe"), p);
            if (duration <= 0) {
                return metadataOnly(path, size, "无法读取视频时长（ffprobe 失败），帧提取已跳过。");
            }

            List<double[]> timestamps = new ArrayList<>();
            for (int i = 0; i < frameCount; i++) {
                // Evenly spaced, avoiding t=0 (first frame often black).
                double t = duration * (i + 0.5) / frameCount;
                timestamps.add(new double[]{t});
            }

            Path tempDir = Files.createTempDirectory("majo-video-");
            try {
                List<ContentBlock> contents = new ArrayList<>();
                int extracted = 0;
                for (int i = 0; i < timestamps.size(); i++) {
                    Path frame = tempDir.resolve("frame-" + i + ".png");
                    if (!extractFrame(ffmpeg, p, timestamps.get(i)[0], frame)) {
                        continue;
                    }
                    byte[] scaled = scaleToPng(frame);
                    if (scaled == null) {
                        continue;
                    }
                    extracted++;
                    if (extracted == 1) {
                        contents.add(TextBlock.builder()
                                .text("视频关键帧已加载: " + path + " (" + size + " bytes, 时长 "
                                        + String.format(Locale.ROOT, "%.1f", duration) + "s)，"
                                        + "以下为 " + timestamps.size() + " 个均匀时间点中的 " + extracted
                                        + " 帧成功提取结果。")
                                .build());
                    }
                    contents.add(ImageBlock.builder()
                            .source(new Base64Source("image/png",
                                    Base64.getEncoder().encodeToString(scaled)))
                            .maxPixels(MAX_EDGE * MAX_EDGE)
                            .build());
                }
                if (extracted == 0) {
                    return metadataOnly(path, size, "帧提取失败（ffmpeg 未产出任何帧），仅返回元信息。");
                }
                return ToolResultBlock.of(contents);
            } finally {
                deleteRecursively(tempDir);
            }
        } catch (Exception e) {
            return ToolResultBlock.text("错误: " + e.getMessage());
        }
    }

    // ── ffmpeg helpers ───────────────────────────────────────────────

    /** Extract one frame at the given timestamp; false on failure. */
    private static boolean extractFrame(String ffmpeg, Path video, double seconds, Path out) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-y",
                    "-ss", String.format(Locale.ROOT, "%.3f", seconds),
                    "-i", video.toString(),
                    "-frames:v", "1",
                    "-vf", "scale='min(" + MAX_EDGE + ",iw)':'min(" + MAX_EDGE + ",ih)':force_original_aspect_ratio=decrease",
                    out.toString());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            if (!proc.waitFor(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0 && Files.isRegularFile(out);
        } catch (Exception e) {
            log.debug("[view-video] frame extraction failed at {}s: {}", seconds, e.getMessage());
            return false;
        }
    }

    /** Read duration via ffprobe; -1 when unavailable. */
    private static double probeDurationSeconds(String ffprobe, Path video) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffprobe, "-v", "error",
                    "-show_entries", "format=duration", "-of", "csv=p=0", video.toString());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            String out;
            try (var is = proc.getInputStream()) {
                out = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            }
            if (!proc.waitFor(FFPROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return -1;
            }
            return proc.exitValue() == 0 ? Double.parseDouble(out) : -1;
        } catch (Exception e) {
            log.debug("[view-video] ffprobe failed: {}", e.getMessage());
            return -1;
        }
    }

    /** Downscale a frame to MAX_EDGE and re-encode as PNG bytes. */
    private static byte[] scaleToPng(Path frame) {
        try {
            BufferedImage img = ImageIO.read(frame.toFile());
            if (img == null) {
                return null;
            }
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
            return baos.toByteArray();
        } catch (Exception e) {
            log.debug("[view-video] frame decode failed: {}", e.getMessage());
            return null;
        }
    }

    /** Locate a binary on PATH, or null. */
    static String detectBinary(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String[] names = windows
                ? new String[]{name + ".exe", name + ".cmd", name + ".bat", name}
                : new String[]{name};
        for (String dir : path.split("[;]")) {
            if (dir.isBlank()) {
                continue;
            }
            for (String n : names) {
                Path candidate = Path.of(dir.strip(), n);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    private static ToolResultBlock metadataOnly(String path, long size, String note) {
        return ToolResultBlock.of(TextBlock.builder()
                .text("视频信息: " + path + " (" + size + " bytes)\n" + note)
                .build());
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            log.debug("[view-video] temp cleanup failed: {}", e.getMessage());
        }
    }
}
