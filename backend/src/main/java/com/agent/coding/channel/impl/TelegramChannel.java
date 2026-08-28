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

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Telegram bot via long polling (getUpdates) — full duplex, no public URL
 * required. Config: bot_token, base_url (default api.telegram.org),
 * http_proxy (optional).
 */
@Component
public class TelegramChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong offset = new AtomicLong(0);
    private volatile Thread poller;
    private volatile HttpClient http;
    private volatile Map<String, Object> config;
    private volatile ChannelDispatcher dispatcher;

    @Override
    public String id() {
        return "telegram";
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        String token = SkillService.str(config.get("bot_token"));
        if (token.isBlank()) {
            throw new IllegalArgumentException("telegram: bot_token 不能为空");
        }
        this.config = config;
        this.dispatcher = dispatcher;
        this.http = buildHttp(config);
        running.set(true);
        poller = Thread.ofPlatform().name("telegram-poll").daemon(true).start(this::pollLoop);
        log.info("[telegram] polling started");
    }

    @Override
    public void stop() {
        running.set(false);
        Thread t = poller;
        if (t != null) {
            t.interrupt();
            poller = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                String token = SkillService.str(config.get("bot_token"));
                String base = SkillService.str(config.get("base_url"), "https://api.telegram.org");
                if (!base.endsWith("/")) base += "/";
                URI uri = URI.create(base + "bot" + token + "/getUpdates?timeout=25&offset=" + offset.get());
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(35)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.warn("[telegram] getUpdates HTTP {}", resp.statusCode());
                    Thread.sleep(3000);
                    continue;
                }
                JsonNode root = MAPPER.readTree(resp.body());
                if (!root.path("ok").asBoolean()) {
                    log.warn("[telegram] api error: {}", root.path("description").asText());
                    Thread.sleep(3000);
                    continue;
                }
                for (JsonNode upd : root.path("result")) {
                    long id = upd.path("update_id").asLong();
                    offset.set(id + 1);
                    handleUpdate(upd);
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("[telegram] poll error: {}", e.getMessage());
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void handleUpdate(JsonNode upd) {
        JsonNode msg = upd.path("message");
        if (msg.isMissingNode()) {
            return; // ignore edited/deleted/etc.
        }
        if (msg.path("from").path("is_bot").asBoolean()) {
            return;
        }
        long chatId = msg.path("chat").path("id").asLong();
        String fromId = msg.path("from").path("id").asText();
        String name = msg.path("from").path("first_name").asText();
        String text = msg.path("text").asText(null);
        if (text == null || text.isBlank()) {
            return;
        }
        String chatType = msg.path("chat").path("type").asText("private");
        String groupId = "private".equals(chatType) ? null : String.valueOf(chatId);
        ChannelMessage cm = new ChannelMessage(id(), fromId, name, groupId, text, String.valueOf(chatId));
        dispatcher.dispatch(config, cm, (to, reply) -> sendReply(to, reply));
    }

    private void sendReply(String chatId, String text) {
        try {
            String token = SkillService.str(config.get("bot_token"));
            String base = SkillService.str(config.get("base_url"), "https://api.telegram.org");
            if (!base.endsWith("/")) base += "/";
            String payload = MAPPER.writeValueAsString(Map.of(
                    "chat_id", Long.parseLong(chatId), "text", text));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[telegram] send failed: HTTP {} {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("[telegram] send error: {}", e.getMessage());
        }
    }

    private static HttpClient buildHttp(Map<String, Object> config) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));
        String proxy = SkillService.str(config.get("http_proxy"));
        if (!proxy.isBlank()) {
            try {
                URI p = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
                b.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), p.getPort() == -1 ? 8080 : p.getPort())));
            } catch (Exception e) {
                log.warn("[telegram] invalid proxy '{}': {}", proxy, e.getMessage());
            }
        }
        return b.build();
    }
}
