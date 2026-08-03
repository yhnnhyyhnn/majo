package com.agent.coding.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/** Maps chat-id → execution state, exactly matching qwenpaw's TaskTracker (plural).
 *  Register on SSE start, deregister on complete/error so GET /loops/status can
 *  report "running" vs "idle" to the frontend loop store. */
@Component
public class TaskTracker {

    private final ConcurrentHashMap<String, String> status = new ConcurrentHashMap<>();

    public void setRunning(String chatId) {
        if (chatId != null && !chatId.isBlank()) {
            status.put(chatId, "running");
        }
    }

    public void setDone(String chatId) {
        if (chatId != null) {
            status.remove(chatId);
        }
    }

    public String getStatus(String chatId) {
        if (chatId == null) return "idle";
        return status.getOrDefault(chatId, "idle");
    }
}
