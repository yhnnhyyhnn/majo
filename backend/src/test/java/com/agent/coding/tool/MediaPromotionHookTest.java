package com.agent.coding.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tool-result media promotion: audio blocks from view_audio (#8083)
 * promote into the context the same way image blocks always have — a
 * follow-up user message carrying the tool's text note plus the media.
 */
class MediaPromotionHookTest {

    private final MediaPromotionHook hook = new MediaPromotionHook();

    private Agent agentWithContext(List<Msg> context) {
        AgentState state = mock(AgentState.class);
        when(state.contextMutable()).thenReturn(context);
        Agent agent = mock(Agent.class);
        when(agent.getAgentState()).thenReturn(state);
        return agent;
    }

    private static PostActingEvent eventWith(Agent agent, ContentBlock... output) {
        PostActingEvent event = mock(PostActingEvent.class);
        when(event.getToolResult()).thenReturn(ToolResultBlock.of(List.of(output)));
        when(event.getAgent()).thenReturn(agent);
        return event;
    }

    @Test
    @SuppressWarnings("unchecked")
    void promotesAudioBlockWithNote() {
        List<Msg> context = new ArrayList<>();
        Agent agent = agentWithContext(context);

        hook.onEvent(eventWith(agent,
                TextBlock.builder().text("音频已加载到上下文: a.mp3").build(),
                AudioBlock.builder().source(new Base64Source("audio/mpeg", "aWQz")).build()))
                .block();

        assertEquals(1, context.size());
        Msg promoted = context.get(0);
        List<ContentBlock> content = promoted.getContent();
        assertEquals(2, content.size());
        assertTrue(content.get(0) instanceof TextBlock t && t.getText().contains("a.mp3"));
        assertTrue(content.get(1) instanceof AudioBlock);
    }

    @Test
    @SuppressWarnings("unchecked")
    void promotesMixedImageAndAudioBlocks() {
        List<Msg> context = new ArrayList<>();
        Agent agent = agentWithContext(context);

        hook.onEvent(eventWith(agent,
                TextBlock.builder().text("mixed").build(),
                ImageBlock.builder().source(new Base64Source("image/png", "aVBOR")).build(),
                AudioBlock.builder().source(new Base64Source("audio/wav", "UklGRg==")).build()))
                .block();

        assertEquals(1, context.size());
        List<ContentBlock> content = context.get(0).getContent();
        assertEquals(3, content.size());
        assertTrue(content.stream().anyMatch(b -> b instanceof ImageBlock));
        assertTrue(content.stream().anyMatch(b -> b instanceof AudioBlock));
    }

    @Test
    @SuppressWarnings("unchecked")
    void textOnlyResultPromotesNothing() {
        List<Msg> context = new ArrayList<>();
        Agent agent = agentWithContext(context);

        Object out = hook.onEvent(eventWith(agent,
                TextBlock.builder().text("纯文本结果").build())).block();

        assertTrue(context.isEmpty(), "no media → no promotion");
        assertTrue(out instanceof PostActingEvent, "event passes through untouched");
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingAgentStatePromotesNothing() {
        PostActingEvent event = mock(PostActingEvent.class);
        when(event.getToolResult()).thenReturn(ToolResultBlock.of(List.of(
                AudioBlock.builder().source(new Base64Source("audio/wav", "UklGRg==")).build())));
        when(event.getAgent()).thenReturn(null);

        hook.onEvent(event).block();
        // No exception, event passes through.
        assertTrue(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonPostActingEventsPassThrough() {
        io.agentscope.core.hook.PreActingEvent other = mock(io.agentscope.core.hook.PreActingEvent.class);
        Object out = hook.onEvent(other).block();
        assertEquals(other, out);
    }
}
