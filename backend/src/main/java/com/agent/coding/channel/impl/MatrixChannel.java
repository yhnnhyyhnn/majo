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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Matrix client — long-polling /sync + send via the client-server API.
 * Config: homeserver, user_id, access_token.
 */
@Component
public class MatrixChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(MatrixChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> config;
    private volatile ChannelDispatcher dispatcher;
    private volatile String since;

    @Override
    public String id() {
        return "matrix";
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        String hs = SkillService.str(config.get("homeserver"));
        if (hs.isBlank() || SkillService.str(config.get("access_token")).isBlank()) {
            throw new IllegalArgumentException("matrix: homeserver 和 access_token 必填");
        }
        this.config = config;
        this.dispatcher = dispatcher;
        running.set(true);
        Thread.ofPlatform().name("matrix-sync").daemon(true).start(this::syncLoop);
        log.info("[matrix] sync started");
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void syncLoop() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        while (running.get()) {
            try {
                String hs = SkillService.str(config.get("homeserver"));
                if (!hs.endsWith("/")) hs += "/";
                String token = SkillService.str(config.get("access_token"));
                String sinceParam = since != null ? "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8) : "";
                URI uri = URI.create(hs + "_matrix/client/v3/sync?timeout=30000" + sinceParam
                        + "&access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.warn("[matrix] sync HTTP {}: {}", resp.statusCode(), resp.body());
                    Thread.sleep(5000);
                    continue;
                }
                JsonNode root = MAPPER.readTree(resp.body());
                since = root.path("next_batch").asText(since);
                JsonNode rooms = root.path("rooms").path("join");
                rooms.fields().forEachRemaining(e -> handleRoom(e.getKey(), e.getValue()));
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                log.warn("[matrix] sync error: {}", e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void handleRoom(String roomId, JsonNode room) {
        String me = SkillService.str(config.get("user_id"));
        JsonNode timeline = room.path("timeline").path("events");
        for (JsonNode ev : timeline) {
            String type = ev.path("type").asText();
            if (!"m.room.message".equals(type)) {
                continue;
            }
            String sender = ev.path("sender").asText();
            if (sender.equals(me) || sender.isBlank()) {
                continue;
            }
            String msgType = ev.path("content").path("msgtype").asText();
            if (!"m.text".equals(msgType) && !"m.notice".equals(msgType)) {
                continue;
            }
            String body = ev.path("content").path("body").asText(null);
            if (body == null || body.isBlank()) {
                continue;
            }
            String userId = sender.startsWith("@") ? sender.substring(1) : sender;
            ChannelMessage cm = new ChannelMessage(id(), userId, userId, roomId, body, roomId);
            dispatcher.dispatch(config, cm, (to, reply) -> sendReply(to, reply, roomId));
        }
    }

    private void sendReply(String ignored, String text, String roomId) {
        try {
            String hs = SkillService.str(config.get("homeserver"));
            if (!hs.endsWith("/")) hs += "/";
            String token = SkillService.str(config.get("access_token"));
            String txn = "majo-" + UUID.randomUUID();
            String path = hs + "_matrix/client/v3/rooms/"
                    + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                    + "/send/m.room.message/" + txn
                    + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            String payload = MAPPER.writeValueAsString(Map.of(
                    "msgtype", "m.text", "body", text,
                    "content", Map.of("msgtype", "m.text", "body", text)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("[matrix] send HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[matrix] send error: {}", e.getMessage());
        }
    }
}
