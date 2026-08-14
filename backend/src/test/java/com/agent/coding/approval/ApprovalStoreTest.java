package com.agent.coding.approval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

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
}
