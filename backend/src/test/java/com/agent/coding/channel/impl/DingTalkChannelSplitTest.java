package com.agent.coding.channel.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DingTalk long-reply splitting (qwenpaw #8341 semantics): newline-boundary
 * chunks within the webhook budget, hard-split overlong single lines, and
 * code fences kept renderable across chunk boundaries.
 */
class DingTalkChannelSplitTest {

    @Test
    void shortTextIsSingleChunk() {
        assertEquals(List.of("你好"), DingTalkChannel.splitText("你好"));
        assertEquals(List.of(""), DingTalkChannel.splitText(""));
    }

    @Test
    void splitsAtNewlineBoundariesWithinBudget() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("第").append(i).append("行的一些中文内容用来撑长度\n");
        }
        List<String> chunks = DingTalkChannel.splitText(sb.toString());

        assertTrue(chunks.size() >= 2, "long text must split");
        StringBuilder rejoined = new StringBuilder();
        for (String c : chunks) {
            assertTrue(c.length() <= DingTalkChannel.MAX_CHUNK_CHARS,
                    "chunk over budget: " + c.length());
            rejoined.append(c);
        }
        assertEquals(sb.toString(), rejoined.toString(), "content preserved verbatim");
    }

    @Test
    void overlongSingleLineIsHardSplit() {
        String line = "x".repeat(DingTalkChannel.MAX_CHUNK_CHARS * 2 + 100);
        List<String> chunks = DingTalkChannel.splitText(line);

        assertTrue(chunks.size() >= 3);
        StringBuilder rejoined = new StringBuilder();
        for (String c : chunks) {
            assertTrue(c.length() <= DingTalkChannel.MAX_CHUNK_CHARS,
                    "chunk over budget: " + c.length());
            rejoined.append(c);
        }
        assertEquals(line, rejoined.toString().replace("\n", ""), "hard-split content preserved");
    }

    @Test
    void codeFenceStaysRenderableAcrossChunks() {
        StringBuilder sb = new StringBuilder();
        sb.append("开头说明\n```java\n");
        for (int i = 0; i < 400; i++) {
            sb.append("int someVariable").append(i).append(" = ").append(i).append("; // line comment\n");
        }
        sb.append("```\n结尾说明\n");
        List<String> chunks = DingTalkChannel.splitText(sb.toString());

        assertTrue(chunks.size() >= 2);
        // Every chunk with an odd number of fences would render broken;
        // each chunk here must be fence-balanced.
        for (String c : chunks) {
            long fences = c.lines().filter(l -> l.strip().startsWith("```")).count();
            assertEquals(0, fences % 2, "unbalanced fence in chunk: " + c.substring(0, Math.min(60, c.length())));
        }
        assertTrue(chunks.get(1).startsWith("```java"), "continuation reopens the fence");
    }
}
