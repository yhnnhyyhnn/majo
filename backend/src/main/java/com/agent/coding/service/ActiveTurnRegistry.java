package com.agent.coding.service;

import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the currently streaming console turns (chat id → agent) so
 * {@code POST /console/chat/stop} can interrupt the run instead of being a
 * stub. Ported alongside QwenPaw's stop-cancellation semantics (#7349):
 * stopping a turn must reach the agent — which lets the harness unwind the
 * model call and tool executions — rather than only closing the SSE pipe.
 */
@Service
public class ActiveTurnRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveTurnRegistry.class);

    private final ConcurrentHashMap<String, HarnessAgent> turns = new ConcurrentHashMap<>();

    public void register(String chatId, HarnessAgent agent) {
        if (chatId == null || chatId.isBlank() || agent == null) {
            return;
        }
        turns.put(chatId, agent);
    }

    public void unregister(String chatId) {
        if (chatId != null) {
            turns.remove(chatId);
        }
    }

    /** Whether a live turn is registered for the chat. */
    public boolean isActive(String chatId) {
        return chatId != null && turns.containsKey(chatId);
    }

    public Set<String> activeChatIds() {
        return Set.copyOf(turns.keySet());
    }

    /**
     * Interrupt the live turn for the chat.
     *
     * @return true when a running turn was found and interrupted
     */
    public boolean stop(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return false;
        }
        HarnessAgent agent = turns.remove(chatId);
        if (agent == null) {
            return false;
        }
        try {
            agent.interrupt();
            log.info("[turn-registry] interrupted turn for chat {}", chatId);
            return true;
        } catch (Exception e) {
            log.warn("[turn-registry] interrupt failed for chat {}: {}", chatId, e.getMessage());
            return false;
        }
    }
}
