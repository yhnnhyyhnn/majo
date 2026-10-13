package com.agent.coding.controller;

import com.agent.coding.approval.ApprovalStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the approve/deny endpoint semantics around the resolution races
 * (qwenpaw #8188): a decision that lost to a timeout or an earlier resolve
 * must surface as 409, not a phantom success.
 */
class ApprovalControllerTest {

    private final ApprovalStore store = new ApprovalStore();
    private final ApprovalController controller = new ApprovalController(store);

    @Test
    void approveReturnsSuccess() {
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);

        ResponseEntity<Map<String, Object>> resp = controller.approve(
                Map.of("request_id", req.requestId, "session_id", "s1"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("success"));
    }

    @Test
    void secondApproveConflicts() {
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);
        controller.approve(Map.of("request_id", req.requestId, "session_id", "s1"));

        ResponseEntity<Map<String, Object>> resp = controller.approve(
                Map.of("request_id", req.requestId, "session_id", "s1"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertTrue(String.valueOf(resp.getBody().get("detail")).contains("already resolved"));
    }

    @Test
    void approveAfterWaiterTimedOutConflicts() {
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);
        assertEquals("denied", req.await(30));

        ResponseEntity<Map<String, Object>> resp = controller.approve(
                Map.of("request_id", req.requestId, "session_id", "s1"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }

    @Test
    void unknownRequestIsNotFound() {
        ResponseEntity<Map<String, Object>> resp = controller.approve(
                Map.of("request_id", "missing", "session_id", "s1"));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void sessionMismatchIsForbidden() {
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);

        ResponseEntity<Map<String, Object>> resp = controller.approve(
                Map.of("request_id", req.requestId, "session_id", "other"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void denyAfterResolutionConflicts() {
        ApprovalStore.ApprovalRequest req = store.register("s1", "s1", "default", "bash", "Bash", "HIGH", 60);
        controller.deny(Map.of("request_id", req.requestId, "session_id", "s1"));

        ResponseEntity<Map<String, Object>> resp = controller.deny(
                Map.of("request_id", req.requestId, "session_id", "s1"));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }
}
