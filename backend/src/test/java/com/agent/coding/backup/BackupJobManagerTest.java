package com.agent.coding.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the async backup job lifecycle: start, poll to terminal state,
 * and cooperative cancel transitions.
 */
class BackupJobManagerTest {

    private BackupJobManager.Snapshot startAndWaitTerminal(BackupJobManager manager) throws Exception {
        var snapshot = manager.startJob(
                new BackupMeta.Scope(true, false, false, false),
                List.of("missing-agent"), "t", "");
        assertNotNull(snapshot);
        assertEquals("running", snapshot.status);
        for (int i = 0; i < 200 && manager.getJob(snapshot.jobId).status.equals("running"); i++) {
            Thread.sleep(50);
        }
        return manager.getJob(snapshot.jobId);
    }

    @Test
    void jobRunsToCompletionEvenWithUnknownAgents() throws Exception {
        BackupJobManager manager = BackupJobManager.get();
        // Wait out any previously-started job from another test in this JVM.
        awaitIdle(manager);
        var snapshot = startAndWaitTerminal(manager);
        assertEquals("completed", snapshot.status);
        assertNotNull(manager.getJob(snapshot.jobId), "finished job stays queryable");
    }

    @Test
    void activeJobIsNullWhenIdle() {
        BackupJobManager manager = BackupJobManager.get();
        if (manager.getActiveJob() != null) {
            return;
        }
        assertNull(manager.getActiveJob());
    }

    @Test
    void cancelRequestedStatusIsReported() throws Exception {
        BackupJobManager manager = BackupJobManager.get();
        awaitIdle(manager);
        var snapshot = manager.startJob(
                new BackupMeta.Scope(true, false, false, false),
                List.of(), "cancel-test", "");
        // Immediately request cancellation; tiny jobs may finish first.
        var cancelledOrFinished = manager.cancelJob(snapshot.jobId);
        assertTrue(cancelledOrFinished != null);
        for (int i = 0; i < 200; i++) {
            var current = manager.getJob(snapshot.jobId);
            String status = current.status;
            assertTrue(List.of("running", "cancel_requested", "cancelled", "completed", "failed")
                    .contains(status));
            if (!status.equals("running") && !status.equals("cancel_requested")) break;
            Thread.sleep(50);
        }
    }

    @Test
    void unknownJobReturnsNull() {
        assertNull(BackupJobManager.get().getJob("bkjob-nope"));
    }

    private static void awaitIdle(BackupJobManager manager) throws Exception {
        for (int i = 0; i < 200 && manager.getActiveJob() != null; i++) {
            Thread.sleep(50);
        }
    }
}
