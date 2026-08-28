package com.agent.coding.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns all channel adapters: starts enabled channels, stops disabled ones,
 * and hot-reloads when the config changes.
 */
@Service
public class ChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelRegistry.class);

    private final Map<String, Channel> adapters = new ConcurrentHashMap<>();
    private final ChannelDispatcher dispatcher;

    public ChannelRegistry(ChannelDispatcher dispatcher,
                           List<Channel> channels) {
        this.dispatcher = dispatcher;
        for (Channel c : channels) {
            adapters.put(c.id(), c);
        }
    }

    /** All known channel ids (including unconfigured/disabled ones). */
    public List<String> channelIds() {
        return adapters.keySet().stream().sorted().toList();
    }

    /** Whether a specific channel is currently running. */
    public boolean isRunning(String id) {
        Channel ch = adapters.get(id);
        return ch != null && ch.isRunning();
    }

    /** Stop and restart one channel with the given config. */
    public synchronized void restart(String id, Map<String, Object> cfg) {
        Channel ch = adapters.get(id);
        if (ch == null) {
            return;
        }
        try {
            if (ch.isRunning()) {
                ch.stop();
            }
            boolean enabled = Boolean.TRUE.equals(cfg.get("enabled"));
            if (enabled) {
                ch.start(cfg, dispatcher);
                log.info("[channel] restarted {}", id);
            }
        } catch (Exception e) {
            log.error("[channel] restart failed for {}", id, e);
        }
    }

    /** Reconcile every channel against the given config map. */
    public synchronized void reload(Map<String, Object> channelsConfig) {
        Map<String, Object> cfg = channelsConfig == null ? Map.of() : channelsConfig;
        for (Map.Entry<String, Channel> e : adapters.entrySet()) {
            String id = e.getKey();
            Channel ch = e.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> chCfg = cfg.get(id) instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : new LinkedHashMap<>();
            boolean enabled = Boolean.TRUE.equals(chCfg.get("enabled"));
            if (enabled && !ch.isRunning()) {
                try {
                    ch.start(chCfg, dispatcher);
                    log.info("[channel] started {}", id);
                } catch (Exception ex) {
                    log.error("[channel] failed to start {}", id, ex);
                }
            } else if (!enabled && ch.isRunning()) {
                try {
                    ch.stop();
                    log.info("[channel] stopped {}", id);
                } catch (Exception ex) {
                    log.error("[channel] failed to stop {}", id, ex);
                }
            }
        }
    }

    /** Stop all channels (application shutdown). */
    public void stopAll() {
        for (Channel ch : adapters.values()) {
            try {
                if (ch.isRunning()) {
                    ch.stop();
                }
            } catch (Exception e) {
                log.warn("[channel] error stopping {}", ch.id(), e);
            }
        }
    }
}
