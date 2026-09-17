package com.agent.coding.acp;

import com.agent.coding.approval.ApprovalStore;
import com.agent.coding.tool.ToolSessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACP permission bridge (phase 2): a runner permission request surfaces as
 * a pending approval on the majo session; the user's decision maps back to
 * the ACP outcome payload (selected with optionId / cancelled). Without an
 * interactive session context the request is denied immediately.
 */
class AcpPermissionBridgeTest {

    private final ApprovalStore store = new ApprovalStore();
    private final AcpPermissionBridge bridge = new AcpPermissionBridge(store);

    private static JsonNode params() {
        try {
            var root = new ObjectMapper().createObjectNode();
            root.putObject("toolCall").put("title", "rm -rf tmp");
            var options = root.putArray("options");
            options.addObject().put("optionId", "allow").put("name", "Allow").put("kind", "allow_once");
            options.addObject().put("optionId", "reject").put("name", "Reject").put("kind", "reject_once");
            return root;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ApprovalStore storeOf() throws Exception {
        Field f = AcpPermissionBridge.class.getDeclaredField("approvalStore");
        f.setAccessible(true);
        return (ApprovalStore) f.get(bridge);
    }

    @AfterEach
    void clearContext() {
        ToolSessionContext.clear();
    }

    @Test
    void approvalMapsToSelectedOutcome() throws Exception {
        ToolSessionContext.set("sess-acp-1", "default");
        // Simulate the user approving the card shortly after it appears.
        Thread user = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(200);
                var pending = storeOf().listPending();
                if (!pending.isEmpty()) {
                    storeOf().resolve(pending.get(0).requestId, "approved", null);
                }
            } catch (Exception ignored) {
            }
        });
        user.start();

        Map<String, Object> payload = bridge.resolve(params(), 5);
        user.join();

        var outcome = (Map<?, ?>) payload.get("outcome");
        assertEquals("selected", outcome.get("outcome"));
        assertEquals("allow", outcome.get("optionId"));
    }

    @Test
    void timeoutDeniesWithCancelled() {
        ToolSessionContext.set("sess-acp-2", "default");
        Map<String, Object> payload = bridge.resolve(params(), 1);
        var outcome = (Map<?, ?>) payload.get("outcome");
        assertEquals("cancelled", outcome.get("outcome"));
    }

    @Test
    void nonInteractiveContextDeniesImmediately() {
        ToolSessionContext.clear();
        long start = System.nanoTime();
        Map<String, Object> payload = bridge.resolve(params(), 60);
        var outcome = (Map<?, ?>) payload.get("outcome");
        assertEquals("cancelled", outcome.get("outcome"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000,
                "non-interactive deny must be immediate");
    }
}
