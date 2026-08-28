package com.agent.coding.subagent;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the background subagent task registry.
 */
class SubagentTaskRegistryTest {

    @Test
    void registerCreatesRunningTask() {
        SubagentTaskRegistry r = new SubagentTaskRegistry();
        var t = r.register("default");
        assertNotNull(t.taskId);
        assertTrue(t.taskId.startsWith("task_"));
        assertEquals("running", t.status);
        assertEquals("default", t.agentId);
        assertEquals(r.get(t.taskId), t);
    }

    @Test
    void completeAndFailUpdateStatus() {
        SubagentTaskRegistry r = new SubagentTaskRegistry();
        var t = r.register("default");
        r.complete(t.taskId, "done!");
        assertEquals("completed", r.get(t.taskId).status);
        assertEquals("done!", r.get(t.taskId).result);

        var t2 = r.register("default");
        r.fail(t2.taskId, "boom");
        assertEquals("failed", r.get(t2.taskId).status);
        assertEquals("boom", r.get(t2.taskId).error);
    }

    @Test
    void cancelOnlyRunningTasks() {
        SubagentTaskRegistry r = new SubagentTaskRegistry();
        var t = r.register("default");
        assertTrue(r.cancel(t.taskId));
        assertEquals("cancelled", t.status);
        // Cannot cancel twice
        assertTrue(!r.cancel(t.taskId));
        // Cannot cancel a completed task
        var t2 = r.register("default");
        r.complete(t2.taskId, "x");
        assertTrue(!r.cancel(t2.taskId));
    }

    @Test
    void listFiltersByStatus() {
        SubagentTaskRegistry r = new SubagentTaskRegistry();
        var running = r.register("a");
        var done = r.register("b");
        r.complete(done.taskId, "ok");
        assertTrue(r.list("running").stream().anyMatch(t -> t.taskId.equals(running.taskId)));
        assertTrue(r.list("completed").stream().anyMatch(t -> t.taskId.equals(done.taskId)));
        assertEquals(2, r.list(null).size());
    }

    @Test
    void sweepRemovesExpiredCompletedTasks() {
        SubagentTaskRegistry r = new SubagentTaskRegistry();
        var done = r.register("a");
        r.complete(done.taskId, "ok");
        // Age the task beyond TTL
        done.createdAt = Instant.now().minusSeconds(SubagentTaskRegistry.TTL_MINUTES * 60 + 60);
        r.sweep();
        assertNull(r.get(done.taskId));
    }

    @Test
    void sweepKeepsRunningTasksEvenWhenOld() {
        SubagentTaskRegistry r = new SubagentTaskRegistry();
        var running = r.register("a");
        running.createdAt = Instant.now().minusSeconds(SubagentTaskRegistry.TTL_MINUTES * 60 + 60);
        r.sweep();
        assertNotNull(r.get(running.taskId));
    }

    @Test
    void capBoundsRegistrySize() {
        SubagentTaskRegistry r = new SubagentTaskRegistry();
        // register beyond the cap; all completed so sweep can evict
        for (int i = 0; i < SubagentTaskRegistry.MAX_ENTRIES + 50; i++) {
            var t = r.register("a");
            r.complete(t.taskId, "x");
        }
        r.sweep();
        assertTrue(r.size() <= SubagentTaskRegistry.MAX_ENTRIES,
                "registry size " + r.size() + " exceeds cap");
    }
}
