package com.agent.coding.subagent;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry for background subagent tasks (submit_to_agent /
 * check_agent_task). Entries expire after {@code TTL_MINUTES} to bound
 * memory.
 */
@Component
public class SubagentTaskRegistry {

    public static final long TTL_MINUTES = 30;
    public static final long MAX_ENTRIES = 200;

    /** A background subagent task. */
    public static final class Task {
        public final String taskId;
        public final String agentId;
        public Instant createdAt;
        public volatile String status;   // running | completed | failed | cancelled
        public volatile String result;
        public volatile String error;

        public Task(String taskId, String agentId) {
            this.taskId = taskId;
            this.agentId = agentId;
            this.createdAt = Instant.now();
            this.status = "running";
        }
    }

    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    public Task register(String agentId) {
        sweep();
        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Task task = new Task(taskId, agentId);
        tasks.put(taskId, task);
        return task;
    }

    public Task get(String taskId) {
        return tasks.get(taskId);
    }

    public void complete(String taskId, String result) {
        Task task = tasks.get(taskId);
        if (task != null) {
            task.status = "completed";
            task.result = result;
        }
    }

    public void fail(String taskId, String error) {
        Task task = tasks.get(taskId);
        if (task != null) {
            task.status = "failed";
            task.error = error;
        }
    }

    public boolean cancel(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null || !"running".equals(task.status)) {
            return false;
        }
        task.status = "cancelled";
        return true;
    }

    public List<Task> list(String status) {
        List<Task> result = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (status == null || status.equals(t.status)) {
                result.add(t);
            }
        }
        return result;
    }

    public int size() {
        return tasks.size();
    }

    /** Remove expired and cancelled entries. Called on register and list. */
    public int sweep() {
        Instant cutoff = Instant.now().minusSeconds(TTL_MINUTES * 60);
        int removed = 0;
        for (Map.Entry<String, Task> e : tasks.entrySet()) {
            Task t = e.getValue();
            boolean expired = t.createdAt.isBefore(cutoff) && !"running".equals(t.status);
            if (expired) {
                tasks.remove(e.getKey(), t);
                removed++;
            }
        }
        // Hard cap: drop the oldest non-running entries if we exceed MAX_ENTRIES.
        if (tasks.size() > MAX_ENTRIES) {
            List<Task> byAge = new ArrayList<>(tasks.values());
            byAge.sort((a, b) -> a.createdAt.compareTo(b.createdAt));
            for (Task t : byAge) {
                if (tasks.size() <= MAX_ENTRIES) {
                    break;
                }
                if (!"running".equals(t.status) && tasks.remove(t.taskId, t)) {
                    removed++;
                }
            }
        }
        return removed;
    }
}
