package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * view_audio behavior (#8083, majo's audio path): path/format/size
 * validation, native passthrough for formatter-supported formats, and the
 * ffmpeg transcode branch — guarded by ffmpeg availability like
 * ViewVideoToolTest, so the suite passes on machines without ffmpeg.
 */
class ViewAudioToolTest {

    @TempDir
    Path workspace;

    private final ViewAudioTool tool = new ViewAudioTool();

    @AfterEach
    void cleanUp() {
        WorkspaceContext.clear();
    }

    @Test
    void rejectsBlankAndMissingPaths() {
        WorkspaceContext.set(workspace.toString());
        assertTrue(firstText(tool.viewAudio(" ")).contains("path"));
        assertTrue(firstText(tool.viewAudio("nope.mp3")).contains("不存在"));
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        WorkspaceContext.set(workspace.toString());
        Files.write(workspace.resolve("track.mid"), new byte[]{1, 2, 3});

        String text = firstText(tool.viewAudio("track.mid"));
        assertTrue(text.contains("不支持的音频格式"), text);
    }

    @Test
    void mp3PassesThroughAsAudioBlock() throws Exception {
        WorkspaceContext.set(workspace.toString());
        byte[] payload = new byte[2048];
        payload[0] = 'I';
        payload[1] = 'D';
        payload[2] = '3';
        Files.write(workspace.resolve("note.mp3"), payload);

        ToolResultBlock out = tool.viewAudio("note.mp3");

        AudioBlock audio = firstAudio(out);
        assertNotNull(audio, "an AudioBlock must ride the result");
        Base64Source source = (Base64Source) audio.getSource();
        assertEquals("audio/mpeg", source.getMediaType());
        assertArrayEquals(payload, Base64.getDecoder().decode(source.getData()));
        assertTrue(firstText(out).contains("音频已加载"), firstText(out));
    }

    @Test
    void wavPassesThroughWithWavMediaType() throws Exception {
        WorkspaceContext.set(workspace.toString());
        Files.write(workspace.resolve("clip.wav"), minimalWav());

        ToolResultBlock out = tool.viewAudio("clip.wav");

        assertEquals("audio/wav",
                ((Base64Source) firstAudio(out).getSource()).getMediaType());
    }

    @Test
    void m4aTranscodesViaFfmpegOrFailsWithGuidance() throws Exception {
        WorkspaceContext.set(workspace.toString());
        // A real m4a is generated with ffmpeg when available; otherwise a
        // dummy file still exercises the same branch (transcode fails).
        Path m4a = workspace.resolve("voice.m4a");
        if (ViewVideoTool.detectBinary("ffmpeg") != null) {
            ProcessBuilder pb = new ProcessBuilder(ViewVideoTool.detectBinary("ffmpeg"), "-y",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=0.2",
                    "-c:a", "aac", m4a.toString());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            assertTrue(pb.start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
                    "ffmpeg probe generation timed out");
        } else {
            Files.write(m4a, new byte[]{1, 2, 3});
        }

        ToolResultBlock out = tool.viewAudio("voice.m4a");

        if (ViewVideoTool.detectBinary("ffmpeg") != null) {
            Base64Source source = (Base64Source) firstAudio(out).getSource();
            assertEquals("audio/wav", source.getMediaType());
            byte[] wav = Base64.getDecoder().decode(source.getData());
            assertTrue(wav.length > 44 && wav[0] == 'R' && wav[1] == 'I'
                    && wav[2] == 'F' && wav[3] == 'F', "decoded payload is a RIFF WAV");
        } else {
            String text = firstText(out);
            assertTrue(text.contains("ffmpeg"), text);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────

    /** Minimal 16-bit mono 8kHz WAV header with a handful of samples. */
    private static byte[] minimalWav() {
        int dataLen = 16;
        byte[] wav = new byte[44 + dataLen];
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        putInt(wav, 4, 36 + dataLen);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        putInt(wav, 16, 16);
        putShort(wav, 20, (short) 1);
        putShort(wav, 22, (short) 1);
        putInt(wav, 24, 8000);
        putInt(wav, 28, 8000 * 2);
        putShort(wav, 32, (short) 2);
        putShort(wav, 34, (short) 16);
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        putInt(wav, 40, dataLen);
        for (int i = 0; i < dataLen; i++) {
            wav[44 + i] = (byte) (i * 7);
        }
        return wav;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static void putShort(byte[] b, int off, short v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    private static String firstText(ToolResultBlock out) {
        return out.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst().orElse("");
    }

    private static AudioBlock firstAudio(ToolResultBlock out) {
        List<ContentBlock> blocks = out.getOutput();
        assertNotNull(blocks);
        return blocks.stream()
                .filter(b -> b instanceof AudioBlock)
                .map(b -> (AudioBlock) b)
                .findFirst().orElse(null);
    }
}
