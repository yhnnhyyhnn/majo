package com.agent.coding.channel.impl;

import com.agent.coding.channel.Channel;
import com.agent.coding.channel.ChannelDispatcher;
import com.agent.coding.channel.ChannelMessage;
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
        dispatcher.dispatch(config, msg, (to, reply) -> sendReply(to, reply, token));
    }

    private void sendReply(String messageId, String text, String token) {
        try {
            String content = MAPPER.writeValueAsString(Map.of("text", text));
            String payload = MAPPER.writeValueAsString(Map.of("msg_type", "text", "content", content));
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
}
