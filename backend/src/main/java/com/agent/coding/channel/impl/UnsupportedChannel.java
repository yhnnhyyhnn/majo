package com.agent.coding.channel.impl;

import com.agent.coding.channel.Channel;
import com.agent.coding.channel.ChannelDispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Placeholder adapter for channels that are not implementable in majo
 * (platform-specific protocols / external services). The channel appears in
 * the config UI, but enabling it reports a clear startup error.
 */
public class UnsupportedChannel implements Channel {

    private final String id;
    private final String reason;

    public UnsupportedChannel(String id, String reason) {
        this.id = id;
        this.reason = reason;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        throw new IllegalArgumentException("channel '" + id + "' 在 majo 中未实现: " + reason);
    }

    @Override
    public void stop() {
        // no-op
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Configuration
    public static class UnsupportedChannelsConfig {

        @Bean
        UnsupportedChannel imessageChannel() {
            return new UnsupportedChannel("imessage", "iMessage 仅支持 macOS 平台");
        }

        @Bean
        UnsupportedChannel mqttChannel() {
            return new UnsupportedChannel("mqtt", "需要 MQTT 客户端库(Paho)，majo 采用零依赖实现");
        }

        @Bean
        UnsupportedChannel voiceChannel() {
            return new UnsupportedChannel("voice", "Twilio 语音电话需要 Twilio 服务与媒体流");
        }

        @Bean
        UnsupportedChannel sipChannel() {
            return new UnsupportedChannel("sip", "SIP 语音需要 LiveKit/SIP 网关");
        }

        @Bean
        UnsupportedChannel xiaoyiChannel() {
            return new UnsupportedChannel("xiaoyi", "中国电信小翼管家为私有协议");
        }

        @Bean
        UnsupportedChannel yuanbaoChannel() {
            return new UnsupportedChannel("yuanbao", "腾讯元宝需要官方私有接入");
        }
    }
}
