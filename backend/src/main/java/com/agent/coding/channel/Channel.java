package com.agent.coding.channel;

import java.util.Map;

/**
 * A messaging channel adapter. Implementations connect to a chat platform,
 * receive incoming messages and hand them to the {@link ChannelDispatcher},
 * and send replies back through the platform API.
 *
 * <p>Lifecycle is driven by {@link ChannelRegistry}: {@link #start} is
 * called with the channel's config when enabled, {@link #stop} on disable
 * or shutdown. All adapters are pure-JDK (HttpClient / java.net.http
 * WebSocket) — no external SDKs.
 */
public interface Channel {

    /** Stable channel id (must match the frontend ChannelConfig key). */
    String id();

    /**
     * Connect and start consuming messages.
     *
     * @param config     this channel's config map (agents.json channels.<id>)
     * @param dispatcher receives incoming messages
     */
    void start(Map<String, Object> config, ChannelDispatcher dispatcher);

    /** Disconnect and stop consuming messages. */
    void stop();

    boolean isRunning();

    /**
     * Send a text message to a recipient (chat/group id from the platform).
     *
     * @return null on success, or an error description
     */
    default String sendText(Map<String, Object> config, String to, String text) {
        return "channel " + id() + " does not support sending";
    }
}
