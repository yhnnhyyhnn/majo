package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Load an audio file from the workspace into the LLM context for audio
 * understanding (qwenpaw #8083, majo's audio-path first step). The tool
 * returns an {@link AudioBlock} inside the result; {@link
 * MediaPromotionHook} then promotes it into the conversation so the next
 * model call carries the audio.
 *
 * <p>WAV/MP3/FLAC/Opus pass through unchanged (the formatter maps them to
 * {@code input_audio} parts); other common containers (m4a/aac/ogg/webm/
 * amr) are transcoded to 16-bit PCM WAV via ffmpeg — mirroring {@link
 * ViewVideoTool}'s ffmpeg dependency handling, a missing ffmpeg is a clear
 * error, never a silent drop. Transcription (qwenpaw's "auto" mode) is a
 * separate missing subsystem and stays out of scope.
 */
@Component
public class ViewAudioTool {

    private static final Logger log = LoggerFactory.getLogger(ViewAudioTool.class);

    /** Extensions the formatter can carry natively → base64 media types. */
    private static final Map<String, String> DIRECT = Map.of(
            ".mp3", "audio/mpeg",
            ".wav", "audio/wav",
            ".flac", "audio/flac",
            ".opus", "audio/opus");

    /** Containers transcoded to WAV before sending. */
    private static final List<String> CONVERT_TO_WAV = List.of(
            ".m4a", ".aac", ".ogg", ".webm", ".amr");

    private static final long MAX_BYTES = 15L * 1024 * 1024;
    /** Post-conversion cap: decoded PCM easily outgrows compressed input. */
    private static final long MAX_WAV_BYTES = 20L * 1024 * 1024;
    private static final int FFMPEG_TIMEOUT_SECONDS = 60;

    @Tool(name = "view_audio", description = "加载工作区中的音频文件到上下文中供听觉分析(语音转写、音频内容理解)。"
            + "支持 mp3/wav/flac/opus 直传,m4a/aac/ogg/webm/amr 自动转码(需要 ffmpeg)")
    public ToolResultBlock viewAudio(
        @ToolParam(name = "path", description = "音频文件路径") String path
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
                return ToolResultBlock.text("错误: 音频过大 (" + size + " bytes)，上限 15MB");
            }
            String name = path.toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? "" : name.substring(dot);

            String mime = DIRECT.get(ext);
            byte[] bytes;
            if (mime != null) {
                bytes = Files.readAllBytes(p);
            } else if (CONVERT_TO_WAV.contains(ext)) {
                Path wav = convertToWav(p);
                if (wav == null) {
                    return ToolResultBlock.text("错误: 音频转码失败。"
                            + "安装 ffmpeg 后可自动将 " + ext + " 转为 WAV;或先将音频转换为 mp3/wav 再试。");
                }
                try {
                    bytes = Files.readAllBytes(wav);
                    if (bytes.length > MAX_WAV_BYTES) {
                        return ToolResultBlock.text("错误: 转码后音频过大 (" + bytes.length
                                + " bytes，上限 20MB)。请截取较短的音频片段后重试。");
                    }
                    mime = "audio/wav";
                } finally {
                    Files.deleteIfExists(wav);
                }
            } else {
                return ToolResultBlock.text("错误: 不支持的音频格式: " + (ext.isEmpty() ? path : ext));
            }

            String b64 = Base64.getEncoder().encodeToString(bytes);
            TextBlock text = TextBlock.builder()
                    .text("音频已加载到上下文: " + path + " (" + bytes.length + " bytes, " + mime + ")")
                    .build();
            AudioBlock audio = AudioBlock.builder()
                    .source(new Base64Source(mime, b64))
                    .build();
            return ToolResultBlock.of(List.of(text, audio));
        } catch (Exception e) {
            return ToolResultBlock.text("错误: " + e.getMessage());
        }
    }

    /** Transcode to 16-bit PCM WAV next to the source; null on failure. */
    private static Path convertToWav(Path input) {
        String ffmpeg = ViewVideoTool.detectBinary("ffmpeg");
        if (ffmpeg == null) {
            return null;
        }
        try {
            Path out = Files.createTempFile("majo_audio_", ".wav");
            ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-y",
                    "-i", input.toString(),
                    "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1",
                    out.toString());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            // Drain stdout so a chatty ffmpeg cannot block on a full pipe.
            proc.getInputStream().readAllBytes();
            if (!proc.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                Files.deleteIfExists(out);
                return null;
            }
            if (proc.exitValue() != 0 || !Files.isRegularFile(out) || Files.size(out) == 0) {
                Files.deleteIfExists(out);
                return null;
            }
            return out;
        } catch (Exception e) {
            log.debug("[view-audio] ffmpeg transcode failed: {}", e.getMessage());
            return null;
        }
    }
}
