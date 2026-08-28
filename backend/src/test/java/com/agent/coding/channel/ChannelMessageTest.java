package com.agent.coding.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChannelMessageTest {

    @Test
    void privateMessageUsesSenderAsReplyTarget() {
        ChannelMessage m = new ChannelMessage("telegram", "u1", "Alice", null, "hi", null);
        assertEquals("u1", m.replyTo());
        assertEquals("u1", m.identity());
    }

    @Test
    void groupMessageUsesGroupAsReplyTargetAndCompositeIdentity() {
        ChannelMessage m = new ChannelMessage("telegram", "u1", "Alice", "g1", "hi", null);
        assertEquals("g1", m.replyTo());
        assertEquals("g1:u1", m.identity());
    }

    @Test
    void explicitReplyTargetWins() {
        ChannelMessage m = new ChannelMessage("feishu", "ou_1", "Bob", "oc_1", "hi", "om_1");
        assertEquals("om_1", m.replyTo());
        assertEquals("oc_1", m.groupId());
        assertEquals("oc_1:ou_1", m.identity());
    }
}
