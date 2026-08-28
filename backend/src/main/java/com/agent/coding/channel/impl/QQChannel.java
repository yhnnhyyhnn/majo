package com.agent.coding.channel.impl;

import com.agent.coding.channel.Channel;
import com.agent.coding.channel.ChannelDispatcher;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * QQ 官方机器人 — 发送走官方开放平台 API（用户 openid 私信），接收走
 * webhook 回调(需公网 URL，见 ChannelWebhookController)。access_token
 * 自动获取并缓存到过期。
 */
@Component
public class QQChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(QQChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> cachedToken = new AtomicReference<>(null);
    private final AtomicLong tokenExpiry = new AtomicLong(0);
    private final AtomicInteger msgSeq = new AtomicInteger(1);

    @Override
    public String id() {
        return "qq";
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
        try {
            String token = accessToken(config);
            String payload = MAPPER.writeValueAsString(Map.of(
                    "content", text, "msg_type", 0, "msg_seq", msgSeq.getAndIncrement()));
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.sgroup.qq.com/v2/users/" + to + "/messages"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "QQBot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                return "qq: HTTP " + resp.statusCode() + ": " + resp.body();
            }
            return null;
        } catch (Exception e) {
            return "qq: " + e.getMessage();
        }
    }

    /** 群聊回复（GROUP_AT_MESSAGE 场景）。 */
    public String sendGroupText(Map<String, Object> config, String groupOpenid, String text) {
        try {
            String token = accessToken(config);
            String payload = MAPPER.writeValueAsString(Map.of(
                    "content", text, "msg_type", 0, "msg_seq", msgSeq.getAndIncrement()));
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.sgroup.qq.com/v2/groups/" + groupOpenid + "/messages"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "QQBot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                return "qq: HTTP " + resp.statusCode() + ": " + resp.body();
            }
            return null;
        } catch (Exception e) {
            return "qq: " + e.getMessage();
        }
    }

    private String accessToken(Map<String, Object> config) throws Exception {
        String cached = cachedToken.get();
        if (cached != null && Instant.now().toEpochMilli() < tokenExpiry.get() - 60_000) {
            return cached;
        }
        String appId = SkillService.str(config.get("app_id"));
        String secret = SkillService.str(config.get("client_secret"));
        String payload = MAPPER.writeValueAsString(Map.of("appId", appId, "clientSecret", secret));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://bots.qq.com/app/getAppAccessToken"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("getAppAccessToken HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        String token = root.path("access_token").asText();
        long expires = root.path("expires_in").asLong(7200);
        cachedToken.set(token);
        tokenExpiry.set(Instant.now().toEpochMilli() + expires * 1000);
        return token;
    }
}
