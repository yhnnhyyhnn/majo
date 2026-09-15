package com.agent.coding.agent;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests for one-shot overflow recovery semantics (#7748 port). */
class OverflowRecoveryTest {

    /** Class name starts with "TextBlock" so recover() marks it meaningful. */
    private static final class TextBlockDeltaEventFake extends AgentEvent {
        @Override public AgentEventType getType() { return AgentEventType.TEXT_BLOCK_DELTA; }
    }

    private static RuntimeException overflow(String message) {
        return new RuntimeException(message);
    }

    @Test
    void classifiesOverflowAcrossProviderPhrasings() {
        assertTrue(OverflowRecovery.isOverflow(
                overflow("This model's maximum context length is 8192 tokens")));
        assertTrue(OverflowRecovery.isOverflow(
                new RuntimeException("wrapped", overflow("prompt is too long"))));
        assertFalse(OverflowRecovery.isOverflow(overflow("connection reset")));
        assertFalse(OverflowRecovery.isOverflow(new IllegalStateException("bad state")));
    }

    @Test
    void nonOverflowErrorPropagatesWithoutRetry() {
        HarnessAgent agent = mock(HarnessAgent.class);
        UserMessage prompt = new UserMessage("hi");

        List<AgentEvent> out = OverflowRecovery.recover(
                        Flux.error(overflow("connection reset")), agent, prompt, null)
                .onErrorResume(e -> Flux.empty())
                .collectList().block();

        assertTrue(out == null || out.isEmpty());
        org.mockito.Mockito.verify(agent, org.mockito.Mockito.never())
                .streamEvents(any(UserMessage.class), any(RuntimeContext.class));
    }

    @Test
    void meaningfulOutputBlocksRecovery() {
        HarnessAgent agent = mock(HarnessAgent.class);
        UserMessage prompt = new UserMessage("hi");
        TextBlockDeltaEventFake textEvent = new TextBlockDeltaEventFake();

        List<AgentEvent> out = OverflowRecovery.recover(
                        Flux.concat(Flux.just(textEvent), Flux.error(overflow("maximum context length"))),
                        agent, prompt, null)
                .onErrorResume(e -> Flux.empty())
                .collectList().block();

        assertEquals(1, out.size());
        org.mockito.Mockito.verify(agent, org.mockito.Mockito.never())
                .streamEvents(any(UserMessage.class), any(RuntimeContext.class));
    }

    @Test
    void overflowWithNoMeaningfulOutputCompactsAndRetriesOnce() {
        List<Msg> context = new ArrayList<>();
        context.add(Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text("earlier question").build()).build());
        context.add(Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("answer").build()).build());
        context.add(Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text("hi").build()).build());

        AgentState state = mock(AgentState.class);
        when(state.contextMutable()).thenReturn(context);
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getAgentState()).thenReturn(state);
        AgentEvent done = mock(AgentEvent.class);
        when(agent.streamEvents(any(UserMessage.class), any())).thenReturn(Flux.just(done));

        UserMessage prompt = new UserMessage("hi");
        List<AgentEvent> out = OverflowRecovery.recover(
                        Flux.error(overflow("maximum context length exceeded")),
                        agent, prompt, null)
                .collectList().block();

        assertEquals(1, out.size());
        // The duplicate user message was removed before the retry.
        assertEquals(2, context.size());
        assertEquals("answer", ((TextBlock) context.get(1).getContent().get(0)).getText());
    }

    @Test
    void compactionFailsWhenNothingToFree() {
        List<Msg> context = new ArrayList<>();
        context.add(Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("answer").build()).build());
        AgentState state = mock(AgentState.class);
        when(state.contextMutable()).thenReturn(context);
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getAgentState()).thenReturn(state);

        assertFalse(OverflowRecovery.compactContext(agent, "unrelated prompt"));
    }
}
