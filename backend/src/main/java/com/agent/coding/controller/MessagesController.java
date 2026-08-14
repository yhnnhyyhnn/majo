package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.inbox.InboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outbound message sending, ported from qwenpaw app/routers/messages.py.
 * Delivers a text message to a channel on behalf of an agent. Majo currently
 * implements the console channel (persisted as an inbox event); other
 * channels (dingtalk/feishu/discord) are not connected and return 404.
 */
@RestController
@RequestMapping("/api/messages")
@CrossOrigin(origins = "*")
public class MessagesController {

    private static final Logger log = LoggerFactory.getLogger(MessagesController.class);

    private final InboxStore inboxStore;

    public MessagesController(InboxStore inboxStore) {
        this.inboxStore = inboxStore;
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        String channel = str(body.get("channel"));
        String targetUser = str(body.get("target_user"));
        String targetSession = str(body.get("target_session"));
        String text = str(body.get("text"));
        String resolvedAgent = (agentId == null || agentId.isBlank()) ? "default" : agentId;

        if (channel.isBlank() || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "channel and text are required"));
        }
        if (!AgentStore.hasAgent(resolvedAgent)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "Agent not found: " + resolvedAgent));
        }

        log.info("API send_message (agent: {}): channel={} user={} session={} text_len={}",
                resolvedAgent, channel,
                targetUser.length() > 40 ? targetUser.substring(0, 40) : targetUser,
                targetSession.length() > 40 ? targetSession.substring(0, 40) : targetSession,
                text.length());

        if ("console".equals(channel)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("_api_send", true);
            payload.put("agent_id", resolvedAgent);
            payload.put("channel", channel);
            payload.put("target_user", targetUser);
            payload.put("target_session", targetSession);
            inboxStore.appendEvent(resolvedAgent, "console", targetSession,
                    "message", "success",
                    text.length() > 80 ? text.substring(0, 80) : text,
                    text, "info", payload);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Message sent successfully to " + channel));
        }

        log.warn("Channel not found: {}", channel);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "Channel not found: " + channel));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
