package com.agent.coding.channel.impl;

import com.agent.coding.channel.Channel;
import com.agent.coding.channel.ChannelDispatcher;
import com.agent.coding.channel.ChannelMessage;
import com.agent.coding.channel.StreamingReply;
import com.agent.coding.skill.SkillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书自建应用机器人 — 长连接 WebSocket 接收事件，HTTP API 回复。
 * 无需公网 URL。Config: app_id, app_secret, verification_token(可选),
 * domain(可选 feishu/lark)。
 */
@Component
public class FeishuChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> config;
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket ws;

    @Override
    public String id() {
        return "feishu";
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (SkillService.str(config.get("app_id")).isBlank()
                || SkillService.str(config.get("app_secret")).isBlank()) {
            throw new IllegalArgumentException("feishu: app_id / app_secret 必填");
        }
        this.config = config;
        this.dispatcher = dispatcher;
        running.set(true);
        Thread.ofPlatform().name("feishu-ws").daemon(true).start(this::connectLoop);
    }

    @Override
    public void stop() {
        running.set(false);
        WebSocket w = ws;
        if (w != null) {
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private String base() {
        return "lark".equals(SkillService.str(config.get("domain")))
                ? "https://open.larksuite.com" : "https://open.feishu.cn";
    }

    private void connectLoop() {
        while (running.get()) {
            try {
                String token = tenantToken();
                String wsUrl = connectUrl(token);
                connect(wsUrl, token);
            } catch (Exception e) {
                log.warn("[feishu] connect error: {}; retrying in 10s", e.getMessage());
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private String tenantToken() throws Exception {
        String payload = MAPPER.writeValueAsString(Map.of(
                "app_id", SkillService.str(config.get("app_id")),
                "app_secret", SkillService.str(config.get("app_secret"))));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/open-apis/auth/v3/tenant_access_token/internal"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("tenant token HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readTree(resp.body()).path("tenant_access_token").asText();
    }

    private String connectUrl(String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/open-apis/events/websocket/connect"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("ws connect HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readTree(resp.body()).path("data").path("ws_url").asText();
    }

    private void connect(String wsUrl, String token) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        WebSocket socket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(wsUrl), new Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.info("[feishu] long-connection opened");
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String line = buf.toString();
                            buf.setLength(0);
                            try {
                                handleEvent(MAPPER.readTree(line), token);
                            } catch (Exception e) {
                                log.warn("[feishu] bad event: {}", e.getMessage());
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        log.warn("[feishu] ws error: {}", error.getMessage());
                    }
                })
                .join();
        ws = socket;
        try {
            synchronized (this) {
                while (running.get() && !socket.isInputClosed() && !socket.isOutputClosed()) {
                    wait(5000);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleEvent(JsonNode ev, String token) throws Exception {
        String eventType = ev.path("header").path("event_type").asText();
        if (!"im.message.receive_v1".equals(eventType)) {
            return;
        }
        String verifyToken = SkillService.str(config.get("verification_token"));
        if (!verifyToken.isBlank()
                && !verifyToken.equals(ev.path("header").path("token").asText())) {
            log.warn("[feishu] verification token mismatch");
            return;
        }
        JsonNode event = ev.path("event");
        String messageId = event.path("message").path("message_id").asText();
        String chatId = event.path("message").path("chat_id").asText();
        String chatType = event.path("message").path("chat_type").asText("p2p");
        String openId = event.path("sender").path("sender_id").path("open_id").asText();
        String contentJson = event.path("message").path("content").asText("{}");
        String text = MAPPER.readTree(contentJson).path("text").asText(null);
        if (messageId.isBlank() || text == null || text.isBlank()) {
            return;
        }
        String groupId = "group".equals(chatType) ? chatId : null;
        ChannelMessage msg = new ChannelMessage(id(), openId, openId, groupId, text, messageId);
        StreamingReply stream = openStreamingCard(msg, token);
        if (stream != null) {
            dispatcher.dispatch(config, msg, (to, reply) -> sendReply(to, reply, token), stream);
        } else {
            dispatcher.dispatch(config, msg, (to, reply) -> sendReply(to, reply, token));
        }
    }

    /**
     * Open a live card for streaming replies (#7685 groundwork): replies
     * once with an interactive card, keeps its message id, and later
     * updates the card in place. Returns null when card mode is off or the
     * card creation failed — the caller then falls back to the one-shot
     * reply path.
     */
    private StreamingReply openStreamingCard(ChannelMessage msg, String token) {
        if (!"card".equals(SkillService.str(config.get("message_type")))) {
            return null;
        }
        String cardMessageId = createCardReply(msg.replyTo(), token,
                "(正在生成回复…)");
        if (cardMessageId == null) {
            return null;
        }
        return new StreamingReply() {
            private int updateFailures;

            @Override
            public void update(String accumulatedText) {
                if (updateFailures >= 3) {
                    return; // stop hammering a failing endpoint
                }
                try {
                    updateCardMessage(base(), token, cardMessageId, accumulatedText);
                    updateFailures = 0;
                } catch (Exception e) {
                    updateFailures++;
                    log.warn("[feishu] card update failed ({}): {}", updateFailures, e.getMessage());
                }
            }

            @Override
            public void complete(String finalText) {
                try {
                    updateCardMessage(base(), token, cardMessageId, finalText);
                } catch (Exception e) {
                    log.warn("[feishu] card completion failed, falling back to reply: {}", e.getMessage());
                    sendReply(msg.replyTo(), finalText, token);
                }
            }
        };
    }

    private void sendReply(String messageId, String text, String token) {
        try {
            String payload = "card".equals(SkillService.str(config.get("message_type")))
                    ? cardPayload(text)
                    : textPayload(text);
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(base() + "/open-apis/im/v1/messages/" + messageId + "/reply"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("[feishu] reply HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[feishu] reply error: {}", e.getMessage());
        }
    }

    static String textPayload(String text) throws Exception {
        String content = MAPPER.writeValueAsString(Map.of("text", text));
        return MAPPER.writeValueAsString(Map.of("msg_type", "text", "content", content));
    }

    /** Reply to a message with a fresh card; returns the card's message id, or null. */
    static String createCardReply(String messageId, String token, String initialText) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(openApiBase(token) + "/open-apis/im/v1/messages/" + messageId + "/reply"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cardPayload(initialText)))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("[feishu] card reply HTTP {}: {}", resp.statusCode(), resp.body());
                return null;
            }
            String id = MAPPER.readTree(resp.body()).path("data").path("message_id").asText("");
            return id.isBlank() ? null : id;
        } catch (Exception e) {
            log.warn("[feishu] card reply error: {}", e.getMessage());
            return null;
        }
    }

    /** Replace a delivered card message's content (documented "edit message" API). */
    static void updateCardMessage(String apiBase, String token, String messageId, String text) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(apiBase + "/open-apis/im/v1/messages/" + messageId))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(cardPayload(text)))
                    .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    /**
     * The API host for streaming card calls. Extracted so tests can point
     * it at a local fake; production resolves from the configured domain
     * (this static context has no instance config).
     */
    static volatile String apiBaseOverride;

    private static String openApiBase(String token) {
        String override = apiBaseOverride;
        return override != null ? override : "https://open.feishu.cn";
    }

    /**
     * Interactive-card payload with a single markdown element — the reply
     * renders agent markdown (code blocks, lists) that plain-text messages
     * flatten. First step of the "feishu card output" batch: the collapsible
     * reasoning panel of qwenpaw #7685 still needs streaming card updates
     * on top of this.
     */
    static String cardPayload(String text) throws Exception {
        String cardBody = MAPPER.writeValueAsString(Map.of(
                "config", Map.of("wide_screen_mode", true),
                "elements", List.of(Map.of("tag", "markdown", "content", text))));
        return MAPPER.writeValueAsString(Map.of("msg_type", "interactive", "content", cardBody));
    }
}
