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
 * Mattermost 机器人 — WebSocket 实时接收 + REST 发送。Config: url, bot_token。
 */
@Component
public class MattermostChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(MattermostChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> config;
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket ws;
    private volatile String myUserId = "";

    @Override
    public String id() {
        return "mattermost";
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        String url = SkillService.str(config.get("url"));
        if (url.isBlank() || SkillService.str(config.get("bot_token")).isBlank()) {
            throw new IllegalArgumentException("mattermost: url 和 bot_token 必填");
        }
        this.config = config;
        this.dispatcher = dispatcher;
        running.set(true);
        Thread.ofPlatform().name("mattermost-ws").daemon(true).start(this::connectLoop);
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
        String url = SkillService.str(config.get("url"));
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void connectLoop() {
        while (running.get()) {
            try {
                myUserId = me();
                connect();
            } catch (Exception e) {
                log.warn("[mattermost] connect error: {}; retrying in 10s", e.getMessage());
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private String me() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/v4/users/me"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + SkillService.str(config.get("bot_token")))
                .GET()
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("users/me HTTP " + resp.statusCode());
        }
        return MAPPER.readTree(resp.body()).path("id").asText();
    }

    private void connect() throws Exception {
        String wsUrl = base().replaceFirst("^http", "ws") + "/api/v4/websocket?access_token="
                + SkillService.str(config.get("bot_token"));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        WebSocket socket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(wsUrl), new Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.info("[mattermost] websocket connected");
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String line = buf.toString();
                            buf.setLength(0);
                            try {
                                handleEvent(MAPPER.readTree(line));
                            } catch (Exception e) {
                                log.warn("[mattermost] bad event: {}", e.getMessage());
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
                        log.warn("[mattermost] ws error: {}", error.getMessage());
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

    private void handleEvent(JsonNode ev) {
        String event = ev.path("event").asText();
        if (!"posted".equals(event)) {
            return;
        }
        String channelType = ev.path("data").path("channel_type").asText("D");
        try {
            JsonNode post = MAPPER.readTree(ev.path("data").path("post").asText("{}"));
            String userId = post.path("user_id").asText();
            String channelId = post.path("channel_id").asText();
            String text = post.path("message").asText(null);
            if (userId.isBlank() || userId.equals(myUserId) || channelId.isBlank()
                    || text == null || text.isBlank()) {
                return;
            }
            String groupId = "D".equals(channelType) ? null : channelId;
            ChannelMessage msg = new ChannelMessage(id(), userId, userId, groupId, text, channelId);
            dispatcher.dispatch(config, msg, (to, reply) -> sendReply(to, reply));
        } catch (Exception e) {
            log.warn("[mattermost] post parse error: {}", e.getMessage());
        }
    }

    private void sendReply(String channelId, String text) {
        try {
            String payload = MAPPER.writeValueAsString(Map.of("channel_id", channelId, "message", text));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/v4/posts"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + SkillService.str(config.get("bot_token")))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("[mattermost] reply HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[mattermost] reply error: {}", e.getMessage());
        }
    }
}
