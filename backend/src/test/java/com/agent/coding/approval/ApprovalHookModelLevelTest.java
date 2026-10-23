package com.agent.coding.approval;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.repository.ChatRepository;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

/**
 * MODEL approval level (ADR-0021): under the level-based path the decision
 * model's verdict approves (event untouched) or denies (tool call rewritten
 * with the reason); human paths still block on a card, and the MCP "ask"
 * policy path (enforceApproval) always stays human even under MODEL.
 */
class ApprovalHookModelLevelTest {

    private static final String AGENT = "default";

    private ApprovalStore store;
    private DecisionModelGate gate;
    private ApprovalHook hook;
    private String originalLevel;

    @BeforeEach
    void setModelLevel() {
        store = new ApprovalStore();
        gate = mock(DecisionModelGate.class);
        hook = new ApprovalHook(store, mock(ChatRepository.class), gate);
        originalLevel = AgentStore.getApprovalLevel(AGENT);
        AgentStore.updateAgent(AGENT, Map.of("approval_level", "MODEL"));
    }

    @AfterEach
    void restoreLevel() {
        AgentStore.updateAgent(AGENT, Map.of("approval_level", originalLevel));
    }

    private PreActingEvent event(ToolUseBlock toolUse) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(AGENT);
        PreActingEvent event = mock(PreActingEvent.class);
        when(event.getToolUse()).thenReturn(toolUse);
        when(event.getAgent()).thenReturn(agent);
        return event;
    }

    private static ToolUseBlock toolUse() {
        return ToolUseBlock.builder()
                .id("call-1")
                .name("execute_command")
                .input(Map.of("command", "ls"))
                .build();
    }

    @Test
    void modelApproveLeavesEventUntouched() {
        when(gate.judge(anyString(), anyString(), any()))
                .thenReturn(new DecisionModelGate.Verdict(true, "决策模型批准"));
        ToolUseBlock original = toolUse();
        PreActingEvent acting = event(original);

        hook.onEvent(acting).block();

        assertSame(original, acting.getToolUse(), "approval must not rewrite the call");
        assertTrue(store.listPending().isEmpty(), "no human card under MODEL");
    }

    @Test
    void modelDenyRewritesToolCallWithReason() {
        when(gate.judge(anyString(), anyString(), any()))
                .thenReturn(new DecisionModelGate.Verdict(false, "决策模型拒绝该工具调用"));
        ToolUseBlock original = toolUse();
        PreActingEvent acting = event(original);

        hook.onEvent(acting).block();

        ArgumentCaptor<ToolUseBlock> captor = ArgumentCaptor.forClass(ToolUseBlock.class);
        verify(acting, atLeastOnce()).setToolUse(captor.capture());
        ToolUseBlock rewritten = captor.getValue();
        assertNotSame(original, rewritten);
        assertEquals(ToolCallState.FINISHED, rewritten.getState());
        assertTrue(String.valueOf(rewritten.getContent()).contains("决策模型拒绝"));
        assertTrue(store.listPending().isEmpty());
    }

    @Test
    void enforceApprovalStaysHumanEvenUnderModelLevel() throws Exception {
        when(gate.judge(anyString(), anyString(), any())).thenThrow(
                new AssertionError("decision model must not be consulted on the ask path"));
        PreActingEvent acting = event(toolUse());

        CompletableFuture<Void> run = CompletableFuture.runAsync(
                () -> hook.enforceApproval(acting, "mcp ask").block());
        awaitPendingCard();
        store.resolve(store.listPending().get(0).requestId, "denied", "test cleanup");
        run.get(10, TimeUnit.SECONDS);

        ArgumentCaptor<ToolUseBlock> captor = ArgumentCaptor.forClass(ToolUseBlock.class);
        verify(acting, atLeastOnce()).setToolUse(captor.capture());
        assertEquals("User denied this tool call.",
                String.valueOf(captor.getValue().getContent()));
    }

    @Test
    void nonModelLevelKeepsHumanPath() throws Exception {
        AgentStore.updateAgent(AGENT, Map.of("approval_level", "AUTO"));
        PreActingEvent acting = event(toolUse());

        CompletableFuture<Void> run = CompletableFuture.runAsync(
                () -> hook.onEvent(acting).block());
        awaitPendingCard();
        store.resolve(store.listPending().get(0).requestId, "denied", "test cleanup");
        run.get(10, TimeUnit.SECONDS);

        ArgumentCaptor<ToolUseBlock> captor = ArgumentCaptor.forClass(ToolUseBlock.class);
        verify(acting, atLeastOnce()).setToolUse(captor.capture());
        assertEquals("User denied this tool call.",
                String.valueOf(captor.getValue().getContent()));
    }

    /** Wait until the human card surfaces (the hook thread is blocking on it). */
    private void awaitPendingCard() throws Exception {
        for (int i = 0; i < 100 && store.listPending().isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertTrue(!store.listPending().isEmpty(), "pending card never appeared");
    }
}
