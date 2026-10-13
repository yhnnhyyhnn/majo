package com.agent.coding.channel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feishu reply payload construction (the "feishu card output" first step,
 * qwenpaw #7685 groundwork): text stays a plain msg_type=text reply, card
 * mode wraps the reply in an interactive card whose single markdown element
 * renders agent markdown (code blocks, lists) instead of flattening it.
 */
class FeishuChannelPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void textPayloadIsPlainTextReply() throws Exception {
        JsonNode root = MAPPER.readTree(FeishuChannel.textPayload("你好"));

        assertEquals("text", root.path("msg_type").asText());
        assertEquals("你好", MAPPER.readTree(root.path("content").asText())
                .path("text").asText());
    }

    @Test
    void cardPayloadWrapsMarkdownElement() throws Exception {
        String markdown = "标题\n\n```java\nint x = 1;\n```\n- 列表项";
        JsonNode root = MAPPER.readTree(FeishuChannel.cardPayload(markdown));

        assertEquals("interactive", root.path("msg_type").asText());
        JsonNode card = MAPPER.readTree(root.path("content").asText());
        assertTrue(card.path("config").path("wide_screen_mode").asBoolean());
        JsonNode element = card.path("elements").get(0);
        assertEquals("markdown", element.path("tag").asText());
        assertEquals(markdown, element.path("content").asText());
    }

    @Test
    void cardPayloadSurvivesQuotesAndNewlinesInReply() throws Exception {
        String tricky = "包含 \"引号\" 与\n换行 {\"json\":\"形\"}";
        JsonNode root = MAPPER.readTree(FeishuChannel.cardPayload(tricky));
        JsonNode element = MAPPER.readTree(root.path("content").asText())
                .path("elements").get(0);
        assertEquals(tricky, element.path("content").asText());
    }
}
