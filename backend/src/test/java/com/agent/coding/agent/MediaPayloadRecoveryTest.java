package com.agent.coding.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests media-payload rejection recovery (#8010 port): a provider refusing
 * the media bytes retries the turn once with request-only stripping, and
 * the strip stays armed for the agent so replays of the poisoned history
 * can never re-trigger the same 400.
 */
class MediaPayloadRecoveryTest {

    /** Class name starts with "TextBlock" so recover() marks it meaningful. */
    private static final class TextBlockDeltaEventFake extends AgentEvent {
        @Override public AgentEventType getType() { return AgentEventType.TEXT_BLOCK_DELTA; }
    }

    @AfterEach
    void cleanup() {
        MediaPayloadRecovery.resetArmed();
    }

    @Test
    void classifiesPayloadRejectionsAcrossProviderPhrasings() {
        assertTrue(MediaPayloadRecovery.isMediaPayloadRejection(
                new RuntimeException("You have uploaded an unsupported image")));
        assertTrue(MediaPayloadRecovery.isMediaPayloadRejection(
                new RuntimeException("wrapped", new IllegalStateException("image too large"))));
        assertTrue(MediaPayloadRecovery.isMediaPayloadRejection(
                new RuntimeException("unsupported image dimensions: 12000x8000")));
        assertTrue(MediaPayloadRecovery.isMediaPayloadRejection(
                new RuntimeException("cannot decode the audio payload")));
        // No media noun → not a payload rejection (overflow is OverflowRecovery's).
        assertFalse(MediaPayloadRecovery.isMediaPayloadRejection(
                new RuntimeException("request too large")));
        assertFalse(MediaPayloadRecovery.isMediaPayloadRejection(
                new RuntimeException("invalid request")));
        // Content-safety refusals name the media but are not payload faults.
        assertFalse(MediaPayloadRecovery.isMediaPayloadRejection(
                new RuntimeException("image violates our content policy")));
        assertFalse(MediaPayloadRecovery.isMediaPayloadRejection(
                new IllegalStateException("no message")));
    }

    @Test
    void nonMediaErrorPropagatesWithoutRetry() {
        HarnessAgent agent = mock(HarnessAgent.class);
        UserMessage prompt = new UserMessage("hi");

        List<AgentEvent> out = MediaPayloadRecovery.recover(
                        Flux.error(new RuntimeException("connection reset")), agent, prompt, null)
                .onErrorResume(e -> Flux.empty())
                .collectList().block();

        assertTrue(out == null || out.isEmpty());
        verify(agent, never()).streamEvents(any(UserMessage.class), any(RuntimeContext.class));
        assertFalse(MediaPayloadRecovery.isStripArmed(MediaPayloadRecovery.agentNameOf(agent)));
    }

    @Test
    void meaningfulOutputBlocksRecovery() {
        HarnessAgent agent = mock(HarnessAgent.class);
        UserMessage prompt = new UserMessage("hi");

        List<AgentEvent> out = MediaPayloadRecovery.recover(
                        Flux.concat(Flux.just(new TextBlockDeltaEventFake()),
                                Flux.error(new RuntimeException("image too large"))),
                        agent, prompt, null)
                .onErrorResume(e -> Flux.empty())
                .collectList().block();

        assertEquals(1, out.size());
        verify(agent, never()).streamEvents(any(UserMessage.class), any(RuntimeContext.class));
    }

    @Test
    void payloadRejectionRetriesOnceAndArmsStrip() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getName()).thenReturn("agent-1");
        AgentEvent done = mock(AgentEvent.class);
        when(agent.streamEvents(any(UserMessage.class), any()))
                .thenReturn(Flux.just(done));

        UserMessage prompt = new UserMessage("describe the photo");
        List<AgentEvent> out = MediaPayloadRecovery.recover(
                        Flux.error(new RuntimeException("uploaded an unsupported image")),
                        agent, prompt, null)
                .collectList().block();

        assertEquals(1, out.size());
        verify(agent, times(1)).streamEvents(any(UserMessage.class), any());
        assertTrue(MediaPayloadRecovery.isStripArmed("agent-1"));
    }

    @Test
    void secondPayloadRejectionOnTheRetryPropagates() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getName()).thenReturn("agent-2");
        when(agent.streamEvents(any(UserMessage.class), any()))
                .thenReturn(Flux.error(new RuntimeException("image is invalid")));

        UserMessage prompt = new UserMessage("describe the photo");
        List<AgentEvent> out = MediaPayloadRecovery.recover(
                        Flux.error(new RuntimeException("uploaded an unsupported image")),
                        agent, prompt, null)
                .onErrorResume(e -> Flux.empty())
                .collectList().block();

        assertTrue(out == null || out.isEmpty());
        verify(agent, times(1)).streamEvents(any(UserMessage.class), any());
    }
}
