package com.agent.coding.skill;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registry of skill hub install tasks (mirrors the Python router's
 * in-memory {@code _hub_install_tasks} machinery).
 *
 * <ul>
 *   <li>TTL of completed/failed/cancelled tasks: 10 minutes</li>
 *   <li>Max retained history entries: 100</li>
 *   <li>One cancel event per task, checked by the running import thread</li>
 * </ul>
 */
public class SkillTaskManager {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_HISTORY = 100;
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "cancelled");

    private final Map<String, SkillModels.HubInstallTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelEvents = new ConcurrentHashMap<>();
    private final Map<String, Thread> runtimeThreads = new ConcurrentHashMap<>();

    public synchronized void put(SkillModels.HubInstallTask task) {
        cleanupLocked();
        tasks.put(task.taskId, task);
    }

    public void attachCancelEvent(String taskId, AtomicBoolean event) {
        cancelEvents.put(taskId, event);
    }

    public void attachRuntimeThread(String taskId, Thread thread) {
        runtimeThreads.put(taskId, thread);
    }

    /** Get a task, cleaning expired entries first. */
    public synchronized SkillModels.HubInstallTask get(String taskId) {
        cleanupLocked();
        return tasks.get(taskId);
    }

    public AtomicBoolean cancelEvent(String taskId) {
        return cancelEvents.get(taskId);
    }

    public synchronized void setStatus(String taskId, String status) {
        SkillModels.HubInstallTask task = tasks.get(taskId);
        if (task != null) {
            task.status = status;
            task.updatedAt = System.currentTimeMillis();
        }
    }

    public synchronized void finish(String taskId, String status, String error,
                                    Map<String, Object> result) {
        SkillModels.HubInstallTask task = tasks.get(taskId);
        if (task != null) {
            task.status = status;
            task.error = error;
            task.result = result;
            task.updatedAt = System.currentTimeMillis();
        }
        runtimeThreads.remove(taskId);
        cancelEvents.remove(taskId);
        cleanupLocked();
    }

    /** Cancel a task; returns current status after the call. */
    public synchronized String cancel(String taskId) {
        SkillModels.HubInstallTask task = tasks.get(taskId);
        if (task == null) throw new SkillNotFoundException("install task not found");
        if (TERMINAL.contains(task.status)) return task.status;
        AtomicBoolean event = cancelEvents.get(taskId);
        if (event != null) event.set(true);
        task.status = "cancelled";
        task.updatedAt = System.currentTimeMillis();
        return "cancelled";
    }

    /** Remove expired terminal tasks and enforce the history cap. */
    private void cleanupLocked() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, SkillModels.HubInstallTask> e : tasks.entrySet()) {
            SkillModels.HubInstallTask t = e.getValue();
            if (TERMINAL.contains(t.status)
                    && !runtimeThreads.containsKey(e.getKey())
                    && now - t.updatedAt > TTL_MILLIS) {
                expired.add(e.getKey());
            }
        }
        for (String id : expired) {
            tasks.remove(id);
            cancelEvents.remove(id);
        }
        int excess = tasks.size() - MAX_HISTORY;
        if (excess > 0) {
            // remove oldest by updated_at
            List<Map.Entry<String, SkillModels.HubInstallTask>> sorted = new ArrayList<>(tasks.entrySet());
            sorted.sort(Comparator.comparingLong(e -> e.getValue().updatedAt));
            for (int i = 0; i < excess && i < sorted.size(); i++) {
                tasks.remove(sorted.get(i).getKey());
                cancelEvents.remove(sorted.get(i).getKey());
            }
        }
    }
}
