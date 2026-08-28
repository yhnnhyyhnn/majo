package com.agent.coding.channel;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Channel UI schemas (label / description / config_fields) consumed by the
 * console Settings → Channels form. Field definitions mirror the frontend
 * ChannelConfig types.
 */
@Component
public class ChannelSchemas {

    private static Map<String, Object> field(String name, String label, String type) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("label", label);
        f.put("type", type);
        return f;
    }

    private static Map<String, Object> select(String name, String label, String... options) {
        Map<String, Object> f = field(name, label, "select");
        f.put("options", List.of(options));
        return f;
    }

    private static Map<String, Object> schema(String label, String description,
                                              List<Map<String, Object>> fields) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("label", label);
        s.put("description", description);
        s.put("plugin_id", "builtin");
        s.put("config_fields", fields);
        return s;
    }

    private static List<Map<String, Object>> base(boolean withPolicies) {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(field("enabled", "启用", "switch"));
        l.add(field("bot_prefix", "机器人前缀", "text"));
        l.add(field("show_tool_calls", "展示工具调用", "switch"));
        l.add(field("show_tool_results", "展示工具结果", "switch"));
        l.add(field("show_thinking", "展示思考过程", "switch"));
        if (withPolicies) {
            l.add(select("dm_policy", "私聊策略", "open", "allowlist"));
            l.add(select("group_policy", "群聊策略", "open", "allowlist"));
            l.add(field("allow_from", "允许的用户ID(逗号分隔)", "text"));
            l.add(field("require_mention", "群聊需@机器人", "switch"));
        }
        return l;
    }

    private static List<Map<String, Object>> addAll(List<Map<String, Object>> base,
                                                    Map<String, Object>... extra) {
        List<Map<String, Object>> l = new ArrayList<>(base);
        l.addAll(List.of(extra));
        return l;
    }

    private final Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();

    public ChannelSchemas() {
        schemas.put("console", schema("控制台", "Web 控制台渠道(始终可用)", base(false)));

        schemas.put("imessage", schema("iMessage", "Apple iMessage(仅 macOS)",
                addAll(base(true), field("db_path", "数据库路径", "text"),
                        field("poll_sec", "轮询间隔(秒)", "number"))));

        schemas.put("discord", schema("Discord", "Discord 机器人(WebSocket 网关)",
                addAll(base(true), field("bot_token", "Bot Token", "password"),
                        field("http_proxy", "HTTP 代理", "text"),
                        field("http_proxy_auth", "代理认证", "password"))));

        schemas.put("dingtalk", schema("钉钉", "钉钉 Stream 模式机器人",
                addAll(base(true), field("client_id", "Client ID", "text"),
                        field("client_secret", "Client Secret", "password"),
                        field("robot_code", "Robot Code", "text"),
                        select("message_type", "消息类型", "text", "markdown", "card"),
                        field("cron_message_type", "定时消息类型", "text"),
                        field("at_sender_on_reply", "回复时@发送者", "switch"))));

        schemas.put("feishu", schema("飞书", "飞书自建应用机器人",
                addAll(base(true), field("app_id", "App ID", "text"),
                        field("app_secret", "App Secret", "password"),
                        field("encrypt_key", "加密密钥", "password"),
                        field("verification_token", "Verification Token", "password"),
                        select("domain", "平台", "feishu", "lark"),
                        field("media_dir", "媒体目录", "text"))));

        schemas.put("qq", schema("QQ", "QQ 官方机器人",
                addAll(base(true), field("app_id", "App ID", "text"),
                        field("client_secret", "Client Secret", "password"),
                        field("ack_message", "ACK 消息", "text"),
                        field("user_openid", "用户 OpenID", "text"))));

        schemas.put("telegram", schema("Telegram", "Telegram Bot(长轮询)",
                addAll(base(true), field("bot_token", "Bot Token", "password"),
                        field("base_url", "API Base URL", "text"),
                        field("http_proxy", "HTTP 代理", "text"),
                        field("http_proxy_auth", "代理认证", "password"),
                        field("show_typing", "显示正在输入", "switch"))));

        schemas.put("slack", schema("Slack", "Slack Socket Mode 机器人",
                addAll(base(true), field("bot_token", "Bot Token", "password"),
                        field("app_token", "App-Level Token(xapp-)", "password"),
                        field("proxy", "代理", "text"))));

        schemas.put("mqtt", schema("MQTT", "MQTT 消息代理(需 Paho 客户端)",
                addAll(base(true), field("host", "主机", "text"),
                        field("port", "端口", "number"),
                        select("transport", "传输", "tcp", "ssl", "ws"),
                        field("username", "用户名", "text"),
                        field("password", "密码", "password"),
                        field("subscribe_topic", "订阅主题", "text"),
                        field("publish_topic", "发布主题", "text"),
                        field("qos", "QoS", "number"),
                        field("clean_session", "Clean Session", "switch"))));

        schemas.put("matrix", schema("Matrix", "Matrix 客户端(同步轮询)",
                addAll(base(true), field("homeserver", "Homeserver", "text"),
                        field("user_id", "用户 ID", "text"),
                        field("access_token", "Access Token", "password"))));

        schemas.put("mattermost", schema("Mattermost", "Mattermost 机器人",
                addAll(base(true), field("url", "服务器 URL", "text"),
                        field("bot_token", "Bot Token", "password"))));

        schemas.put("wecom", schema("企业微信", "企业微信智能机器人(webhook 收发)",
                addAll(base(true), field("bot_id", "Bot ID", "text"),
                        field("secret", "Secret", "password"),
                        field("webhook_url", "群机器人 Webhook URL(发送用)", "password"),
                        field("callback_token", "回调 Token(接收用)", "password"),
                        field("callback_encoding_aes_key", "回调 EncodingAESKey(接收用)", "password"),
                        field("welcome_text", "欢迎语", "text"),
                        field("max_reconnect_attempts", "最大重连次数", "number"))));

        schemas.put("wechat", schema("微信", "微信中继(需外部中继服务)",
                addAll(base(true), field("base_url", "中继服务 URL", "text"),
                        field("bot_token", "Bot Token", "password"))));

        schemas.put("voice", schema("语音电话", "Twilio 语音(需 Twilio 服务)",
                addAll(base(true), field("twilio_account_sid", "Account SID", "text"),
                        field("twilio_auth_token", "Auth Token", "password"),
                        field("phone_number", "电话号码", "text"),
                        field("tts_provider", "TTS 提供方", "text"),
                        field("stt_provider", "STT 提供方", "text"),
                        field("language", "语言", "text"))));

        schemas.put("sip", schema("SIP 语音", "SIP 语音电话(需 LiveKit/SIP 服务)",
                addAll(base(true), field("sip_server", "SIP 服务器", "text"),
                        field("sip_username", "SIP 用户名", "text"),
                        field("sip_password", "SIP 密码", "password"))));

        schemas.put("xiaoyi", schema("小翼管家", "中国电信小翼管家",
                addAll(base(true), field("ak", "Access Key", "text"),
                        field("sk", "Secret Key", "password"),
                        field("agent_id", "Agent ID", "text"))));

        schemas.put("yuanbao", schema("元宝", "腾讯元宝(需官方接入)",
                addAll(base(true), field("app_id", "App ID", "text"),
                        field("app_secret", "App Secret", "password"),
                        field("api_domain", "API 域名", "text"))));

        schemas.put("onebot", schema("OneBot(QQ)", "连接 OneBot 实现(如 NapCat/GoCQHTTP)",
                addAll(base(true), field("ws_host", "WS 主机", "text"),
                        field("ws_port", "WS 端口", "number"),
                        field("access_token", "Access Token", "password"))));
    }

    /** Schema for a channel id (or an empty schema if unknown). */
    public Map<String, Object> forChannel(String id) {
        Map<String, Object> s = schemas.get(id);
        return s != null ? s : schema(id, id, new ArrayList<>());
    }
}
