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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Discord 机器人 — WebSocket 网关 v10 (IDENTIFY / HEARTBEAT / MESSAGE_CREATE)
 * + REST 发消息。无需公网 URL。Config: bot_token。
 */
@Component
public class DiscordChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DiscordChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int INTENTS = (1 << 9) | (1 << 12) | (1 << 15); // GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> config;
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket ws;
    private volatile Thread heartbeat;

    @Override
    public String id() {
        return "discord";
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        if (SkillService.str(config.get("bot_token")).isBlank()) {
            throw new IllegalArgumentException("discord: bot_token 必填");
        }
        this.config = config;
        this.dispatcher = dispatcher;
        running.set(true);
        Thread.ofPlatform().name("discord-ws").daemon(true).start(this::connectLoop);
    }

    @Override
    public void stop() {
        running.set(false);
        Thread hb = heartbeat;
        if (hb != null) {
            hb.interrupt();
        }
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
                String gatewayUrl = gatewayUrl();
                connect(gatewayUrl);
            } catch (Exception e) {
                log.warn("[discord] connect error: {}; retrying in 10s", e.getMessage());
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private String gatewayUrl() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/gateway/bot"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bot " + SkillService.str(config.get("bot_token")))
                .GET()
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("gateway/bot HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readTree(resp.body()).path("url").asText();
    }

    private void connect(String url) throws Exception {
        String wsUrl = url + "?v=10&encoding=json";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        AtomicBoolean identified = new AtomicBoolean(false);
        AtomicLong heartbeatInterval = new AtomicLong(41_000);
        WebSocket socket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(wsUrl), new Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.info("[discord] gateway connected");
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String line = buf.toString();
                            buf.setLength(0);
                            try {
                                handleOp(MAPPER.readTree(line), webSocket, identified, heartbeatInterval);
                            } catch (Exception e) {
                                log.warn("[discord] bad op: {}", e.getMessage());
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
                        log.warn("[discord] ws error: {}", error.getMessage());
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

    private void handleOp(JsonNode op, WebSocket socket, AtomicBoolean identified,
                          AtomicLong heartbeatInterval) {
        int opcode = op.path("op").asInt(-1);
        switch (opcode) {
            case 10 -> { // HELLO
                heartbeatInterval.set(op.path("d").path("heartbeat_interval").asLong(41_000));
                startHeartbeat(socket, heartbeatInterval);
                sendIdentify(socket);
            }
            case 11 -> { /* HEARTBEAT_ACK — ignore */ }
            case 0 -> handleDispatch(op, socket, identified);
            case 7 -> { // RECONNECT
                log.info("[discord] reconnect requested");
                WebSocket w = ws;
                if (w != null) {
                    try {
                        w.abort();
                    } catch (Exception ignored) {
                    }
                }
            }
            default -> { }
        }
    }

    private void startHeartbeat(WebSocket socket, AtomicLong interval) {
        Thread hb = heartbeat;
        if (hb != null && hb.isAlive()) {
            return;
        }
        heartbeat = Thread.ofPlatform().name("discord-hb").daemon(true).start(() -> {
            while (running.get() && !socket.isOutputClosed()) {
                try {
                    Thread.sleep(interval.get());
                    if (running.get()) {
                        socket.sendText("{\"op\":1,\"d\":null}", true).join();
                    }
                } catch (InterruptedException ie) {
                    return;
                } catch (Exception e) {
                    return;
                }
            }
        });
    }

    private void sendIdentify(WebSocket socket) {
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "op", 2,
                    "d", Map.of(
                            "token", SkillService.str(config.get("bot_token")),
                            "intents", INTENTS,
                            "properties", Map.of(
                                    "os", "java", "browser", "majo", "device", "majo"))));
            socket.sendText(payload, true).join();
        } catch (Exception e) {
            log.warn("[discord] identify failed: {}", e.getMessage());
        }
    }

    private void handleDispatch(JsonNode op, WebSocket socket, AtomicBoolean identified) {
        String t = op.path("t").asText();
        if ("READY".equals(t)) {
            identified.set(true);
            log.info("[discord] ready as {}", op.path("d").path("user").path("username").asText());
            return;
        }
        if (!"MESSAGE_CREATE".equals(t)) {
            return;
        }
        JsonNode d = op.path("d");
        if (d.path("author").path("bot").asBoolean()) {
            return;
        }
        String channelId = d.path("channel_id").asText();
        String userId = d.path("author").path("id").asText();
        String text = d.path("content").asText(null);
        if (channelId.isBlank() || text == null || text.isBlank()) {
            return;
        }
        String name = d.path("author").path("username").asText(userId);
        String guildId = d.path("guild_id").isNull() ? null : d.path("guild_id").asText();
        ChannelMessage msg = new ChannelMessage(id(), userId, name, guildId, text, channelId);
        dispatcher.dispatch(config, msg, (to, reply) -> sendReply(to, reply));
    }

    private void sendReply(String channelId, String text) {
        try {
            String payload = MAPPER.writeValueAsString(Map.of("content", text));
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://discord.com/api/v10/channels/" + channelId + "/messages"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bot " + SkillService.str(config.get("bot_token")))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("[discord] reply HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[discord] reply error: {}", e.getMessage());
        }
    }
}
