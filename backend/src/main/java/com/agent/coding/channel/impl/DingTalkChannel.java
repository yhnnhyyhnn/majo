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
 * 钉钉 Stream 模式机器人 — 通过钉钉网关 WebSocket 接收消息，回复走
 * 消息里的 sessionWebhook。无需公网 URL。Config: client_id,
 * client_secret, robot_code。
 */
@Component
public class DingTalkChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> config;
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket ws;

    @Override
    public String id() {
        return "dingtalk";
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (SkillService.str(config.get("client_id")).isBlank()
                || SkillService.str(config.get("client_secret")).isBlank()) {
            throw new IllegalArgumentException("dingtalk: client_id / client_secret 必填");
        }
        this.config = config;
        this.dispatcher = dispatcher;
        running.set(true);
        Thread.ofPlatform().name("dingtalk-ws").daemon(true).start(this::connectLoop);
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

    private void connectLoop() {
        while (running.get()) {
            try {
                String token = accessToken();
                String wsUrl = openConnection(token);
                connect(wsUrl, token);
            } catch (Exception e) {
                log.warn("[dingtalk] connect error: {}; retrying in 10s", e.getMessage());
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private String accessToken() throws Exception {
        String payload = MAPPER.writeValueAsString(Map.of(
                "appKey", SkillService.str(config.get("client_id")),
                "appSecret", SkillService.str(config.get("client_secret"))));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.dingtalk.com/v1.0/oauth2/accessToken"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("accessToken HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readTree(resp.body()).path("accessToken").asText();
    }

    private String openConnection(String token) throws Exception {
        String payload = MAPPER.writeValueAsString(Map.of(
                "clientId", SkillService.str(config.get("client_id")),
                "clientSecret", SkillService.str(config.get("client_secret")),
                "subscriptions", java.util.List.of(Map.of("topic", "/v1.0/im/bot/messages/get"))));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.dingtalk.com/v1.0/gateway/connections/open"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("x-acs-dingtalk-access-token", token)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("connections/open HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readTree(resp.body()).path("url").asText();
    }

    private void connect(String wsUrl, String token) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        WebSocket socket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(wsUrl), new Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.info("[dingtalk] stream connected");
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String line = buf.toString();
                            buf.setLength(0);
                            try {
                                handleMessage(MAPPER.readTree(line), token);
                            } catch (Exception e) {
                                log.warn("[dingtalk] bad payload: {}", e.getMessage());
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
                        log.warn("[dingtalk] ws error: {}", error.getMessage());
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

    private void handleMessage(JsonNode payload, String token) throws Exception {
        String topic = payload.path("topic").asText();
        if (!"/v1.0/im/bot/messages/get".equals(topic)) {
            return;
        }
        ack(payload, token);
        JsonNode data = payload.path("data");
        String msgType = data.path("msgtype").asText();
        String content = data.path("text").path("content").asText(null);
        if (!"text".equals(msgType) || content == null || content.isBlank()) {
            return;
        }
        // 去掉 @机器人 前缀(单聊/群聊)
        String senderId = data.path("senderStaffId").asText("unknown");
        String senderNick = data.path("senderNick").asText(senderId);
        String convType = data.path("conversationType").asText("1"); // 1单聊 2群聊
        String groupId = "2".equals(convType) ? data.path("conversationId").asText() : null;
        String sessionWebhook = data.path("sessionWebhook").asText();
        String clean = content.replaceAll("@\\s*\\S+", "").trim();
        if (clean.isBlank()) {
            return;
        }
        ChannelMessage msg = new ChannelMessage(id(), senderId, senderNick, groupId, clean, null);
        dispatcher.dispatch(config, msg, (to, reply) -> sendReply(sessionWebhook, reply));
    }

    /** 网关消息 ack（REST 调用）。 */
    private void ack(JsonNode payload, String token) {
        try {
            String eventId = payload.path("eventId").asText();
            JsonNode headers = payload.path("headers");
            Map<String, Object> body = Map.of("requestId", eventId, "headers", headers);
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.dingtalk.com/v1.0/gateway/connections/ack"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("x-acs-dingtalk-access-token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[dingtalk] ack failed: {}", e.getMessage());
        }
    }

    private void sendReply(String sessionWebhook, String text) {
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "msgtype", "text", "text", Map.of("content", text)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(sessionWebhook))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("[dingtalk] reply HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[dingtalk] reply error: {}", e.getMessage());
        }
    }
}
