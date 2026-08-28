package com.agent.coding.channel;

/**
 * An incoming channel message, normalized across platforms.
 */
public record ChannelMessage(
        String channelId,
        String senderId,
        String senderName,
        String groupId,   // null for DMs
        String text,
        String replyTo    // platform-specific reply target (chat id), usually groupId or senderId
) {
    public ChannelMessage {
        replyTo = replyTo == null ? (groupId != null ? groupId : senderId) : replyTo;
    }

    /** Identity used for session/ACL bookkeeping. */
    public String identity() {
        return groupId != null ? groupId + ":" + senderId : senderId;
    }
}
