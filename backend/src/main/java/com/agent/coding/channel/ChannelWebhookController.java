package com.agent.coding.channel;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.channel.impl.QQChannel;
import com.agent.coding.channel.impl.WeComCrypto;
import com.agent.coding.channel.impl.WeChatChannel;
import com.agent.coding.channel.impl.WecomChannel;
import com.agent.coding.skill.SkillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Platform callback webhooks for channels that receive messages via a
 * public URL (wecom / wechat relay / qq official bot). Note: a public
 * URL reachable from the internet is required for these receive paths —
 * the long-connection channels (telegram / dingtalk / feishu / slack /
 * discord / onebot / matrix) do not need one.
 */
@RestController
@RequestMapping("/api/channels")
@CrossOrigin(origins = "*")
public class ChannelWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChannelDispatcher dispatcher;
    private final WecomChannel wecomChannel;
    private final WeChatChannel wechatChannel;
    private final QQChannel qqChannel;

    public ChannelWebhookController(ChannelDispatcher dispatcher,
                                    WecomChannel wecomChannel,
                                    WeChatChannel wechatChannel,
                                    QQChannel qqChannel) {
        this.dispatcher = dispatcher;
        this.wecomChannel = wecomChannel;
        this.wechatChannel = wechatChannel;
        this.qqChannel = qqChannel;
    }

    /** Webhook 渠道必须启用且 running 才接收消息。 */
    private boolean channelEnabled(String id, Map<String, Object> cfg) {
        boolean enabled = Boolean.TRUE.equals(cfg.get("enabled"));
        boolean running = switch (id) {
            case "wecom" -> wecomChannel.isRunning();
            case "wechat" -> wechatChannel.isRunning();
            case "qq" -> qqChannel.isRunning();
            default -> false;
        };
        return enabled && running;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channelConfig(String id) {
        Map<String, Object> config = AgentStore.loadConfig();
        Object ch = config.get("channels");
        if (ch instanceof Map<?, ?> m) {
            Object c = m.get(id);
            if (c instanceof Map<?, ?> cm) {
                return new LinkedHashMap<>((Map<String, Object>) cm);
            }
        }
        return new LinkedHashMap<>();
    }

    // ── 企业微信 ─────────────────────────────────────────────────────

    /** URL 验证: 解密 echostr 原样返回。 */
    @GetMapping(value = "/wecom/webhook", produces = "text/plain")
    public ResponseEntity<String> wecomVerify(@RequestParam String msg_signature,
                                              @RequestParam String timestamp,
                                              @RequestParam String nonce,
                                              @RequestParam String echostr) {
        Map<String, Object> cfg = channelConfig("wecom");
        String token = SkillService.str(cfg.get("callback_token"));
        String aesKey = SkillService.str(cfg.get("callback_encoding_aes_key"));
        if (token.isBlank() || aesKey.isBlank() || !channelEnabled("wecom", cfg)) {
            return ResponseEntity.status(500).body("wecom callback not configured or disabled");
        }
        try {
            if (!WeComCrypto.verifySignature(token, timestamp, nonce, echostr, msg_signature)) {
                return ResponseEntity.status(403).body("signature mismatch");
            }
            return ResponseEntity.ok(WeComCrypto.decrypt(aesKey, echostr));
        } catch (Exception e) {
            log.warn("[wecom] verify failed: {}", e.getMessage());
            return ResponseEntity.status(500).body("decrypt failed");
        }
    }

    /** 消息回调: 校验 + 解密 XML → 分发。 */
    @PostMapping(value = "/wecom/webhook", produces = "text/plain")
    public ResponseEntity<String> wecomMessage(@RequestParam String msg_signature,
                                               @RequestParam String timestamp,
                                               @RequestParam String nonce,
                                               @RequestBody String body) {
        Map<String, Object> cfg = channelConfig("wecom");
        String token = SkillService.str(cfg.get("callback_token"));
        String aesKey = SkillService.str(cfg.get("callback_encoding_aes_key"));
        if (!channelEnabled("wecom", cfg)) {
            return ResponseEntity.ok("success");
        }
        try {
            String encrypt = extractEncrypt(body);
            if (!WeComCrypto.verifySignature(token, timestamp, nonce, encrypt, msg_signature)) {
                return ResponseEntity.status(403).body("signature mismatch");
            }
            String xml = WeComCrypto.decrypt(aesKey, encrypt);
            String from = xmlValue(xml, "FromUserName");
            String content = xmlValue(xml, "Content");
            String msgType = xmlValue(xml, "MsgType");
            if (!"text".equals(msgType) || from.isBlank() || content.isBlank()) {
                return ResponseEntity.ok("success");
            }
            ChannelMessage msg = new ChannelMessage("wecom", from, from, null, content, from);
            dispatcher.dispatch(cfg, msg,
                    (to, reply) -> wecomChannel.sendText(cfg, to, reply));
            return ResponseEntity.ok("success");
        } catch (Exception e) {
            log.warn("[wecom] message handling failed: {}", e.getMessage());
            return ResponseEntity.status(500).body("error");
        }
    }

    private static String extractEncrypt(String xml) {
        int start = xml.indexOf("<Encrypt><![CDATA[");
        if (start < 0) {
            start = xml.indexOf("<Encrypt>");
            int s = start + "<Encrypt>".length();
            int e = xml.indexOf("</Encrypt>", s);
            return xml.substring(s, e);
        }
        int s = start + "<Encrypt><![CDATA[".length();
        int e = xml.indexOf("]]></Encrypt>", s);
        return xml.substring(s, e);
    }

    private static String xmlValue(String xml, String tag) {
        String open = "<" + tag + "><![CDATA[";
        int start = xml.indexOf(open);
        if (start >= 0) {
            int s = start + open.length();
            int e = xml.indexOf("]]></" + tag + ">", s);
            return e < 0 ? "" : xml.substring(s, e);
        }
        String open2 = "<" + tag + ">";
        int s2 = xml.indexOf(open2);
        if (s2 >= 0) {
            int c = s2 + open2.length();
            int e2 = xml.indexOf("</" + tag + ">", c);
            return e2 < 0 ? "" : xml.substring(c, e2);
        }
        return "";
    }

    // ── 微信中继 ─────────────────────────────────────────────────────

    /** 中继服务转发来的消息: {token, sender, name, group, text}。 */
    @PostMapping(value = "/wechat/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> wechatMessage(@RequestBody String rawBody) {
        Map<String, Object> cfg = channelConfig("wechat");
        String expectToken = SkillService.str(cfg.get("bot_token"));
        if (!channelEnabled("wechat", cfg)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "detail", "channel disabled"));
        }
        try {
            JsonNode body = MAPPER.readTree(rawBody);
            String token = body.path("token").asText();
            if (!expectToken.isBlank() && !expectToken.equals(token)) {
                return ResponseEntity.status(403).body(Map.of("detail", "bad token"));
            }
            String sender = body.path("sender").asText();
            String text = body.path("text").asText("");
            if (sender.isBlank() || text.isBlank()) {
                return ResponseEntity.ok(Map.of("status", "ignored"));
            }
            String name = body.path("name").asText(sender);
            String group = body.path("group").isNull() ? null : body.path("group").asText();
            ChannelMessage msg = new ChannelMessage("wechat", sender, name, group, text,
                    group != null ? group : sender);
            dispatcher.dispatch(cfg, msg, (to, reply) -> wechatChannel.sendText(cfg, to, reply));
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            log.warn("[wechat] webhook error: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("detail", e.getMessage()));
        }
    }

    // ── QQ 官方机器人 ────────────────────────────────────────────────

    @PostMapping(value = "/qq/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> qqMessage(@RequestBody String rawBody) {
        Map<String, Object> cfg = channelConfig("qq");
        if (!channelEnabled("qq", cfg)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "detail", "channel disabled"));
        }
        try {
            JsonNode body = MAPPER.readTree(rawBody);
            JsonNode d = body.path("d");
            String t = d.path("t").asText();
            if ("C2C_FRIEND".equals(t)) {
                String openid = d.path("author").path("user_openid").asText();
                String text = qqText(d);
                if (!openid.isBlank() && !text.isBlank()) {
                    ChannelMessage msg = new ChannelMessage("qq", openid, openid, null, text, openid);
                    dispatcher.dispatch(cfg, msg, (to, reply) -> qqChannel.sendText(cfg, to, reply));
                }
                return ResponseEntity.ok(Map.of("status", "ok"));
            }
            if ("GROUP_AT_MESSAGE".equals(t)) {
                String groupOpenid = d.path("group_openid").asText();
                String openid = d.path("author").path("member_openid").asText();
                String text = qqText(d);
                if (!groupOpenid.isBlank() && !text.isBlank()) {
                    ChannelMessage msg = new ChannelMessage("qq", openid, openid,
                            groupOpenid, text, groupOpenid);
                    dispatcher.dispatch(cfg, msg, (to, reply) -> qqChannel.sendGroupText(cfg, to, reply));
                }
                return ResponseEntity.ok(Map.of("status", "ok"));
            }
            return ResponseEntity.ok(Map.of("status", "ignored", "t", t));
        } catch (Exception e) {
            log.warn("[qq] webhook error: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("detail", e.getMessage()));
        }
    }

    private static String qqText(JsonNode d) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode seg : d.path("content")) {
            if ("text".equals(seg.path("type").asText())) {
                sb.append(seg.path("data").path("text").asText(""));
            }
        }
        return sb.toString();
    }
}
