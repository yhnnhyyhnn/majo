package com.agent.coding.approval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the ApprovalStore pending/suspend mechanism (the bridge between tool
 * execution and the frontend approval flow).
 */
@SpringBootTest
class ApprovalStoreTest {

    @Test
    void registerCreatesPendingEntry() {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);
        assertEquals("bash", req.toolName);
        assertTrue(store.listPending().stream().anyMatch(r -> r.requestId.equals(req.requestId)));
    }

    @Test
    void resolveCompletesFutureWithApproved() throws Exception {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "write_file", "Write", "HIGH", 60);
        CompletableFuture<String> future = req.future;

        CompletableFuture<String> result = future.thenApply(d -> d);
        store.resolve(req.requestId, "approved", null);
        assertEquals("approved", result.get());
    }

    @Test
    void resolveCompletesFutureWithDenied() throws Exception {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);

        CompletableFuture<String> result = req.future.thenApply(d -> d);
        store.resolve(req.requestId, "denied", "user says no");
        assertEquals("denied", result.get());
    }

    @Test
    void awaitBlocksUntilResolved() throws Exception {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);

        CompletableFuture<String> waiter = CompletableFuture.supplyAsync(() -> req.await(5000));
        Thread.sleep(100);
        assertTrue(!waiter.isDone());
        store.resolve(req.requestId, "approved", null);
        assertEquals("approved", waiter.get());
    }

    @Test
    void awaitTimesOutAsDenied() {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);
        assertEquals("denied", req.await(50));
    }

    @Test
    void purgeExpiredDeniesStaleRequests() throws Exception {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 1L);
        Thread.sleep(1100);
        store.purgeExpired();
        assertTrue(req.resolved);
        assertEquals("denied", req.decision);
    }

    @Test
    void awaitTimeoutSettlesRequestSoLateApprovalConflicts() {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);

        assertEquals("denied", req.await(50));

        assertTrue(req.resolved);
        assertEquals("denied", req.decision);
        assertEquals("timeout", req.reason);
        // The agent already proceeded with the denial; a late approve must
        // lose the arbiter race instead of reporting a phantom success.
        assertEquals(null, store.resolve(req.requestId, "approved", null));
        assertTrue(store.listPending().isEmpty());
    }

    @Test
    void expiredRequestDeniesLateApproval() throws Exception {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 1L);
        Thread.sleep(1100);

        assertEquals(null, store.resolve(req.requestId, "approved", null));
        assertEquals("denied", req.decision);
        assertEquals("timeout", req.reason);
    }

    @Test
    void decisionResolvedBeforeWaitIsRetained() {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);

        store.resolve(req.requestId, "approved", null);

        // A decision landing before the consumer starts waiting must not be
        // lost to the waiter's own timeout.
        assertEquals("approved", req.await(50));
    }

    @Test
    void doubleResolveOnlyFirstDecisionWins() {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);

        assertTrue(store.resolve(req.requestId, "approved", null) != null);
        assertEquals(null, store.resolve(req.requestId, "denied", "second click"));
        assertEquals("approved", req.decision);
    }

    @Test
    void listPendingSweepsExpiredCards() throws Exception {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest stale = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 1L);
        ApprovalStore.ApprovalRequest fresh = store.register("s2", "s2", "default", "bash", "Bash", "HIGH", 60);
        Thread.sleep(1100);

        List<ApprovalStore.ApprovalRequest> pending = store.listPending();
        assertTrue(pending.stream().noneMatch(r -> r.requestId.equals(stale.requestId)));
        assertTrue(pending.stream().anyMatch(r -> r.requestId.equals(fresh.requestId)));
        assertEquals("denied", stale.decision);
        assertEquals("timeout", stale.reason);
    }

    @Test
    void awaitNeverWaitsPastRequestDeadline() {
        ApprovalStore store = new ApprovalStore();
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 1L);
        long startedAt = System.currentTimeMillis();

        // The 5-minute waiter timeout must clamp to the 1-second deadline.
        assertEquals("denied", req.await(5 * 60 * 1000));
        assertTrue(System.currentTimeMillis() - startedAt < 4500);
    }
}
