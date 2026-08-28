package com.agent.coding.channel.impl;

import com.agent.coding.channel.Channel;
import com.agent.coding.channel.ChannelDispatcher;
import com.agent.coding.skill.SkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信渠道 — 通过外部中继服务收发。发送: POST {base_url}/send 携带
 * {token, receiver, text}；接收: 由中继调用 ChannelWebhookController
 * 的 /api/channels/wechat/webhook 转发消息。需要公网可达的中继服务。
 */
@Component
public class WeChatChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WeChatChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public String id() {
        return "wechat";
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        running.set(true);
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public String sendText(Map<String, Object> config, String to, String text) {
        String base = SkillService.str(config.get("base_url"));
        String token = SkillService.str(config.get("bot_token"));
        if (base.isBlank() || token.isBlank()) {
            return "wechat: 未配置 base_url / bot_token";
        }
        try {
            if (!base.endsWith("/")) base += "/";
            String payload = MAPPER.writeValueAsString(Map.of(
                    "token", token, "receiver", to, "text", text));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "send"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "wechat: HTTP " + resp.statusCode() + ": " + resp.body();
            }
            return null;
        } catch (Exception e) {
            return "wechat: " + e.getMessage();
        }
    }
}
