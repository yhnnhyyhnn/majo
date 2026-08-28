package com.agent.coding.channel;

import com.agent.coding.agent.AgentStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts enabled channels after the application is ready and stops them on
 * shutdown. Config lives in agents.json {@code channels}.
 */
@Component
public class ChannelLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ChannelLifecycle.class);
    private final ChannelRegistry registry;

    public ChannelLifecycle(ChannelRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("[channel] reconciling channels on startup");
        registry.reload(channelsConfig());
    }

    @PreDestroy
    public void shutdown() {
        registry.stopAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channelsConfig() {
        Map<String, Object> config = AgentStore.loadConfig();
        Object ch = config.get("channels");
        return ch instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of();
    }
}
