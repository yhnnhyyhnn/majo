package com.agent.coding.approval;

import com.agent.coding.agent.AgentStore;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MODEL approval level (ADR-0021), driven through the middleware-facing
 * gateAndAwait (ADR-0022): MODEL consults the decision model — approve
 * returns the original block, deny returns a FINISHED rejection with the
 * reason; other levels register a human card keyed by the session id, and
 * humanCardFlow (the MCP "ask" path) always stays human.
 */
class ApprovalHookModelLevelTest {

    private static final String AGENT = "default";
    private static final String SESSION = "s-approval";

    private ApprovalStore store;
    private DecisionModelGate gate;
    private ApprovalHook hook;
    private String originalLevel;

    @BeforeEach
    void setModelLevel() {
        store = new ApprovalStore();
        gate = mock(DecisionModelGate.class);
        hook = new ApprovalHook(store, gate);
        originalLevel = AgentStore.getApprovalLevel(AGENT);
        AgentStore.updateAgent(AGENT, Map.of("approval_level", "MODEL"));
    }

    @AfterEach
    void restoreLevel() {
        AgentStore.updateAgent(AGENT, Map.of("approval_level", originalLevel));
    }

    private static ToolUseBlock toolUse() {
        return ToolUseBlock.builder()
                .id("call-1")
                .name("execute_command")
                .input(Map.of("command", "ls"))
                .build();
    }

    @Test
    void modelApproveReturnsOriginalBlock() {
        when(gate.judge(anyString(), anyString(), any()))
                .thenReturn(new DecisionModelGate.Verdict(true, "决策模型批准"));
        ToolUseBlock original = toolUse();

        ToolUseBlock out = hook.gateAndAwait(SESSION, AGENT, original);

        assertSame(original, out, "approval must not rewrite the call");
        assertTrue(store.listPending().isEmpty(), "no human card under MODEL");
    }

    @Test
    void modelDenyReturnsFinishedRejectionWithReason() {
        when(gate.judge(anyString(), anyString(), any()))
                .thenReturn(new DecisionModelGate.Verdict(false, "决策模型拒绝该工具调用"));
        ToolUseBlock original = toolUse();

        ToolUseBlock out = hook.gateAndAwait(SESSION, AGENT, original);

        assertNotSame(original, out);
        assertEquals(ToolCallState.FINISHED, out.getState());
        assertTrue(String.valueOf(out.getContent()).contains("决策模型拒绝"));
        assertTrue(store.listPending().isEmpty());
    }

    @Test
    void autoLevelRegistersHumanCardKeyedBySession() throws Exception {
        AgentStore.updateAgent(AGENT, Map.of("approval_level", "AUTO"));
        ToolUseBlock original = toolUse();

        CompletableFuture<ToolUseBlock> run = CompletableFuture.supplyAsync(
                () -> hook.gateAndAwait(SESSION, AGENT, original));
        awaitPendingCard();
        ApprovalStore.ApprovalRequest card = store.listPending().get(0);
        assertEquals(SESSION, card.sessionId, "card must be keyed by the runtime session");
        store.resolve(card.requestId, "denied", "test cleanup");

        ToolUseBlock out = run.get(10, TimeUnit.SECONDS);
        assertEquals("User denied this tool call.", String.valueOf(out.getContent()));
    }

    @Test
    void humanCardFlowAlwaysStaysHumanEvenUnderModelLevel() throws Exception {
        when(gate.judge(anyString(), anyString(), any())).thenThrow(
                new AssertionError("decision model must not be consulted on the ask path"));
        ToolUseBlock original = toolUse();

        CompletableFuture<String> run = CompletableFuture.supplyAsync(
                () -> hook.humanCardFlow(SESSION, AGENT, original));
        awaitPendingCard();
        store.resolve(store.listPending().get(0).requestId, "denied", "test cleanup");

        assertEquals("User denied this tool call.", run.get(10, TimeUnit.SECONDS));
    }

    /** Wait until the human card surfaces (the caller thread blocks on it). */
    private void awaitPendingCard() throws Exception {
        for (int i = 0; i < 100 && store.listPending().isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertTrue(!store.listPending().isEmpty(), "pending card never appeared");
    }
}
