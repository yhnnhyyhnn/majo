package com.agent.coding.channel.impl;

import com.agent.coding.channel.Channel;
import com.agent.coding.channel.ChannelDispatcher;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The web console channel — always available, no external connection.
 */
@Component
public class ConsoleChannel implements Channel {

    @Override
    public String id() {
        return "console";
    }

    @Override
    public void start(Map<String, Object> config, ChannelDispatcher dispatcher) {
        // Console is the web UI; nothing to connect.
    }

    @Override
    public void stop() {
        // no-op
    }

    @Override
    public boolean isRunning() {
        return true;
    }
}
