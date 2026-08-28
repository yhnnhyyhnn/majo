package com.agent.coding.channel.impl;

import com.agent.coding.channel.Channel;
import com.agent.coding.channel.ChannelDispatcher;
import com.agent.coding.channel.ChannelMessage;
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
 * 企业微信渠道 — 发送走群机器人 webhook，接收走标准回调(需公网 URL，见
 * ChannelWebhookController)。AES 解密用 {@link WeComCrypto}。
 */
@Component
public class WecomChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WecomChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private final AtomicBoolean running = new AtomicBoolean(false); // 跟随 enabled 状态

    @Override
    public String id() {
        return "wecom";
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
        String webhook = SkillService.str(config.get("webhook_url"));
        if (webhook.isBlank()) {
            return "wecom: 未配置 webhook_url，无法发送";
        }
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "msgtype", "text", "text", Map.of("content", text)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhook))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "wecom: HTTP " + resp.statusCode() + ": " + resp.body();
            }
            return null;
        } catch (Exception e) {
            return "wecom: " + e.getMessage();
        }
    }
}
