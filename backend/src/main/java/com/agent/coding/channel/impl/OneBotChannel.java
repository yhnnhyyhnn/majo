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
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OneBot v11 (QQ) — connects as a WebSocket CLIENT to an existing OneBot
 * server (NapCat / GoCQHTTP / Lagrange), listens for private/group messages
 * and sends replies back via {@code send_msg}.
 */
@Component
public class OneBotChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(OneBotChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> config;
    private volatile ChannelDispatcher dispatcher;
    private volatile WebSocket ws;

    @Override
    public String id() {
        return "onebot";
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        String host = SkillService.str(config.get("ws_host"));
        int port = parseInt(config.get("ws_port"), 6700);
        if (host.isBlank()) {
            throw new IllegalArgumentException("onebot: ws_host 不能为空");
        }
        this.config = config;
        this.dispatcher = dispatcher;
        running.set(true);
        Thread.ofPlatform().name("onebot-ws").daemon(true).start(() -> connectLoop(host, port));
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

    private void connectLoop(String host, int port) {
        String accessToken = SkillService.str(config.get("access_token"));
        while (running.get()) {
            try {
                String wsUrl = "ws://" + host + ":" + port + (accessToken.isBlank() ? "" : "?access_token=" + accessToken);
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
                WebSocket socket = client.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(URI.create(wsUrl), new Listener() {
                            private final StringBuilder buf = new StringBuilder();

                            @Override
                            public void onOpen(WebSocket webSocket) {
                                log.info("[onebot] connected to {}", wsUrl);
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
                                        log.warn("[onebot] bad event: {}", e.getMessage());
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
                                log.warn("[onebot] ws error: {}", error.getMessage());
                            }
                        })
                        .join();
                ws = socket;
                // Keep the thread alive; Listener callbacks run on a WebSocket thread.
                try {
                    synchronized (this) {
                        while (running.get() && !socket.isInputClosed() && !socket.isOutputClosed()) {
                            wait(5000);
                        }
                    }
                } catch (InterruptedException ie) {
                    return;
                }
            } catch (Exception e) {
                log.warn("[onebot] connect failed: {}; retrying in 5s", e.getMessage());
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    private static int parseInt(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void handleEvent(JsonNode ev) {
        String postType = ev.path("post_type").asText();
        if (!"message".equals(postType)) {
            return;
        }
        long userId = ev.path("user_id").asLong();
        if (userId == 0) {
            return;
        }
        String text = ev.path("raw_message").asText(null);
        if (text == null || text.isBlank()) {
            return;
        }
        String messageType = ev.path("message_type").asText("private");
        String groupId = "group".equals(messageType) ? ev.path("group_id").asText() : null;
        String senderId = String.valueOf(userId);
        String name = ev.path("sender").path("card").asText(
                ev.path("sender").path("nickname").asText(senderId));
        ChannelMessage cm = new ChannelMessage(id(), senderId, name, groupId, text,
                "group".equals(messageType) ? groupId : senderId);
        dispatcher.dispatch(config, cm, (to, reply) -> sendReply(to, reply, "group".equals(messageType)));
    }

    private void sendReply(String target, String text, boolean group) {
        try {
            Map<String, Object> body = Map.of(
                    "action", "send_msg",
                    "params", Map.of(group ? "group_id" : "user_id", Long.parseLong(target), "message", text));
            String payload = MAPPER.writeValueAsString(body);
            WebSocket w = ws;
            if (w != null) {
                w.sendText(payload, true).join();
            } else {
                log.warn("[onebot] not connected, drop reply");
            }
        } catch (Exception e) {
            log.warn("[onebot] send error: {}", e.getMessage());
        }
    }
}
