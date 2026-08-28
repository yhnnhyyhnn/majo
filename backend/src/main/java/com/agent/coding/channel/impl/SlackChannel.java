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
 * Slack Socket Mode — 通过 apps.connections.open 拿 WS 地址，接收
 * events_api 消息并在同一 WS 上 ACK，回复走 chat.postMessage。
 * 无需公网 URL。Config: bot_token(xoxb-), app_token(xapp-)。
 */
@Component
public class SlackChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> config;
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket ws;

    @Override
    public String id() {
        return "slack";
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (SkillService.str(config.get("bot_token")).isBlank()
                || SkillService.str(config.get("app_token")).isBlank()) {
            throw new IllegalArgumentException("slack: bot_token 和 app_token 必填");
        }
        this.config = config;
        this.dispatcher = dispatcher;
        running.set(true);
        Thread.ofPlatform().name("slack-ws").daemon(true).start(this::connectLoop);
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
                String url = appsConnectionsOpen();
                connect(url);
            } catch (Exception e) {
                log.warn("[slack] connect error: {}; retrying in 10s", e.getMessage());
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private String appsConnectionsOpen() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://slack.com/api/apps.connections.open"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + SkillService.str(config.get("app_token")))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(resp.body());
        if (!root.path("ok").asBoolean()) {
            throw new IllegalStateException("apps.connections.open: " + root.path("error").asText());
        }
        return root.path("url").asText();
    }

    private void connect(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        WebSocket socket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), new Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.info("[slack] socket mode connected");
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String line = buf.toString();
                            buf.setLength(0);
                            try {
                                handleEnvelope(MAPPER.readTree(line), webSocket);
                            } catch (Exception e) {
                                log.warn("[slack] bad envelope: {}", e.getMessage());
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
                        log.warn("[slack] ws error: {}", error.getMessage());
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

    private void handleEnvelope(JsonNode env, WebSocket socket) {
        String type = env.path("type").asText();
        if ("hello".equals(type)) {
            return;
        }
        // ACK 任何需要 ack 的 envelope
        String envelopeId = env.path("envelope_id").asText(null);
        if (envelopeId != null) {
            try {
                socket.sendText("{\"envelope_id\":\"" + envelopeId + "\"}", true).join();
            } catch (Exception e) {
                log.warn("[slack] ack failed: {}", e.getMessage());
            }
        }
        if (!"events_api".equals(type)) {
            return;
        }
        JsonNode event = env.path("payload").path("event");
        if (!"message".equals(event.path("type").asText())) {
            return;
        }
        if (!event.path("subtype").isMissingNode() || !event.path("bot_id").isMissingNode()) {
            return; // 编辑/删除/机器人消息跳过
        }
        String user = event.path("user").asText();
        String channel = event.path("channel").asText();
        String text = event.path("text").asText(null);
        if (user.isBlank() || channel.isBlank() || text == null || text.isBlank()) {
            return;
        }
        String channelType = event.path("channel_type").asText("im");
        String groupId = "im".equals(channelType) ? null : channel;
        ChannelMessage msg = new ChannelMessage(id(), user, user, groupId, text, channel);
        dispatcher.dispatch(config, msg, (to, reply) -> sendReply(to, reply));
    }

    private void sendReply(String channel, String text) {
        try {
            String payload = MAPPER.writeValueAsString(Map.of("channel", channel, "text", text));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://slack.com/api/chat.postMessage"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + SkillService.str(config.get("bot_token")))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(resp.body());
            if (!root.path("ok").asBoolean()) {
                log.warn("[slack] reply error: {}", root.path("error").asText());
            }
        } catch (Exception e) {
            log.warn("[slack] reply error: {}", e.getMessage());
        }
    }
}
