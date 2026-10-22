package com.agent.coding.channel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feishu streaming-card API calls (#7685 groundwork): card creation parses
 * the reply's message_id, in-place updates PUT the card payload to the
 * message resource, and HTTP failures surface as exceptions the handle
 * translates into fallback behaviour.
 */
class FeishuChannelCardApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String apiBase;
    private final List<String> replyBodies = new CopyOnWriteArrayList<>();
    private final List<String> updatedBodies = new CopyOnWriteArrayList<>();
    private volatile int replyStatus = 200;
    private volatile String replyResponse = "{\"data\":{\"message_id\":\"om_card_1\"}}";
    private volatile int updateStatus = 200;

    @BeforeEach
    void startFakeFeishu() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/open-apis/im/v1/messages/om_src/reply", exchange -> {
            replyBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = replyResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(replyStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.createContext("/open-apis/im/v1/messages/om_card_1", exchange -> {
            updatedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(updateStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopFakeFeishu() {
        FeishuChannel.apiBaseOverride = null;
        server.stop(0);
    }

    @Test
    void createCardReplyParsesMessageIdAndPostsCardPayload() throws Exception {
        FeishuChannel.apiBaseOverride = apiBase;
        String id = FeishuChannel.createCardReply("om_src", "sk-t", "(正在生成回复…)");

        assertEquals("om_card_1", id);
        JsonNode posted = MAPPER.readTree(replyBodies.get(0));
        assertEquals("interactive", posted.path("msg_type").asText());
        JsonNode element = MAPPER.readTree(posted.path("content").asText())
                .path("elements").get(0);
        assertEquals("(正在生成回复…)", element.path("content").asText());
    }

    @Test
    void createFailureReturnsNull() {
        FeishuChannel.apiBaseOverride = apiBase;
        replyStatus = 500;

        assertEquals(null, FeishuChannel.createCardReply("om_src", "sk-t", "x"));
    }

    @Test
    void updateCardMessagePutsCardPayload() throws Exception {
        FeishuChannel.apiBaseOverride = apiBase;
        FeishuChannel.updateCardMessage(apiBase, "sk-t", "om_card_1", "最终回复");

        assertEquals(1, updatedBodies.size());
        JsonNode put = MAPPER.readTree(updatedBodies.get(0));
        assertEquals("interactive", put.path("msg_type").asText());
        assertEquals("最终回复", MAPPER.readTree(put.path("content").asText())
                .path("elements").get(0).path("content").asText());
        // Auth header goes to the fake server too (checked via request trace
        // implicitly: without it the fake would still 200; the contract is
        // asserted at payload level here and header wiring is static).
        assertTrue(updatedBodies.get(0).contains("markdown"));
    }

    @Test
    void updateFailureThrowsForTheHandleToTranslate() {
        FeishuChannel.apiBaseOverride = apiBase;
        updateStatus = 502;

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> FeishuChannel.updateCardMessage(apiBase, "sk-t", "om_card_1", "x"));
    }
}
