package com.agent.coding.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Maps chat-id → execution state, exactly's TaskTracker (plural).
 *  Register on SSE start, deregister on complete/error so GET /loops/status can
 *  report "running" vs "idle" to the frontend loop store. */
@Component
public class TaskTracker {

    private final ConcurrentHashMap<String, String> status = new ConcurrentHashMap<>();
    private volatile long lastRunAt;
    private volatile long lastFinishAt;

    public void setRunning(String chatId) {
        if (chatId != null && !chatId.isBlank()) {
            status.put(chatId, "running");
            lastRunAt = System.currentTimeMillis();
        }
    }

    public void setDone(String chatId) {
        if (chatId != null) {
            status.remove(chatId);
            lastFinishAt = System.currentTimeMillis();
        }
    }

    public String getStatus(String chatId) {
        if (chatId == null) return "idle";
        return status.getOrDefault(chatId, "idle");
    }

    public Map<String, Object> getGlobalStatus() {
        int running = status.size();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", running > 0 ? "running" : "idle");
        result.put("running_task_count", running);
        result.put("last_run_at", lastRunAt > 0 ? java.time.Instant.ofEpochMilli(lastRunAt).toString() : null);
        result.put("last_finish_at", lastFinishAt > 0 ? java.time.Instant.ofEpochMilli(lastFinishAt).toString() : null);
        return result;
    }
}
