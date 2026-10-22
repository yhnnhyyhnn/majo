package com.agent.coding.channel;

import com.agent.coding.ChatService;
import com.agent.coding.accesscontrol.AccessControlStore;
import com.agent.coding.agent.HarnessAgentFactory;
import com.agent.coding.memory.MemoryCommandService;
import com.agent.coding.security.ToolGuardHook;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Streaming dispatch (#7685 groundwork): the first delta streams out
 * immediately, later deltas are time-bounded (at most one update per
 * interval with enough new text), the final content lands in exactly one
 * complete() on every path — success, denial, update failure — and a null
 * handle keeps the legacy one-shot reply untouched.
 */
class ChannelDispatcherStreamingTest {

    private HarnessAgent lastAgent;

    private ChatService chatService() {
        ChatService chatService = mock(ChatService.class);
        com.agent.coding.entity.ChatEntity chat = new com.agent.coding.entity.ChatEntity();
        chat.setId("c1");
        when(chatService.getOrCreateBySession(any(), any(), any())).thenReturn(chat);
        return chatService;
    }

    private ChannelDispatcher dispatcherWithAgent() {
        HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
        when(factory.workspaceFor(any())).thenReturn(java.nio.file.Path.of("."));
        when(factory.modelFor(any(), any())).thenReturn(OpenAIChatModel.builder()
                .apiKey("test").baseUrl("http://127.0.0.1:1").modelName("m").build());
        HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class);
        when(builder.hook(any())).thenReturn(builder);
        when(builder.build()).thenAnswer(inv -> lastAgent);
        when(factory.builder(any(), any(), any(), any())).thenReturn(builder);
        return new ChannelDispatcher(factory, mock(ToolGuardHook.class),
                chatService(), mock(AccessControlStore.class),
                mock(MemoryCommandService.class));
    }

    private ChannelDispatcher dispatcherWithoutModel() {
        HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
        when(factory.workspaceFor(any())).thenReturn(java.nio.file.Path.of("."));
        when(factory.modelFor(any(), any())).thenReturn(null);
        return new ChannelDispatcher(factory, mock(ToolGuardHook.class),
                chatService(), mock(AccessControlStore.class),
                mock(MemoryCommandService.class));
    }

    private static ChannelMessage msg(String text) {
        return new ChannelMessage("feishu", "u1", "u1", null, text, "m-1");
    }

    /** Records updates/completions; complete() marks the handle dead. */
    private static final class RecordingStream implements StreamingReply {
        final List<String> updates = new ArrayList<>();
        String completed;
        RuntimeException updateFailure;

        @Override
        public void update(String accumulatedText) {
            if (updateFailure != null) throw updateFailure;
            updates.add(accumulatedText);
        }

        @Override
        public void complete(String finalText) {
            if (completed != null) throw new IllegalStateException("complete twice");
            completed = finalText;
        }
    }

    private static TextBlockDeltaEvent delta(String text) {
        return new TextBlockDeltaEvent("r", "b", text);
    }

    @Test
    void firstDeltaStreamsImmediatelyAndFastStreamDoesNotSpam() {
        lastAgent = mock(HarnessAgent.class);
        StringBuilder expected = new StringBuilder();
        List<TextBlockDeltaEvent> deltas = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String chunk = "chunk-" + i + " ";
            deltas.add(delta(chunk));
            expected.append(chunk);
        }
        when(lastAgent.streamEvents(any(UserMessage.class), any()))
                .thenReturn(Flux.<io.agentscope.core.event.AgentEvent>fromIterable(deltas));
        ChannelDispatcher d = dispatcherWithAgent();
        RecordingStream stream = new RecordingStream();

        d.dispatch(Map.of(), msg("hello"), (to, reply) -> {}, stream);

        assertEquals(expected.toString().trim(), stream.completed);
        // All deltas land within the throttle interval: exactly the leading
        // update fires, no spam. The first update is a leading snapshot.
        assertEquals(1, stream.updates.size());
        assertEquals("chunk-0 ", stream.updates.get(0));
    }

    @Test
    void intervalExpiryFiresTheNextUpdate() {
        lastAgent = mock(HarnessAgent.class);
        when(lastAgent.streamEvents(any(UserMessage.class), any()))
                .thenReturn(Flux.<io.agentscope.core.event.AgentEvent>just(
                                delta("first "),
                                // Past the 1.5s interval and past the
                                // 80-new-char floor by the last delta.
                                delta("x".repeat(90)),
                                delta("last"))
                        .delayElements(java.time.Duration.ofMillis(1600)));
        ChannelDispatcher d = dispatcherWithAgent();
        RecordingStream stream = new RecordingStream();

        d.dispatch(Map.of(), msg("hello"), (to, reply) -> {}, stream);

        assertEquals("first " + "x".repeat(90) + "last", stream.completed);
        assertTrue(stream.updates.size() >= 2,
                "interval expiry fires another update, got " + stream.updates.size());
        assertEquals("first ", stream.updates.get(0));
    }

    @Test
    void denialPathCompletesInsteadOfOneShot() {
        ChannelDispatcher d = dispatcherWithoutModel();
        RecordingStream stream = new RecordingStream();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dm_policy", "allowlist");
        config.put("allow_from", List.of("someone-else"));

        d.dispatch(config, msg("hi"), (to, reply) -> {}, stream);

        assertEquals("你没有权限使用本服务。", stream.completed);
    }

    @Test
    void updateThrowingNeverBreaksTheRun() {
        lastAgent = mock(HarnessAgent.class);
        when(lastAgent.streamEvents(any(UserMessage.class), any()))
                .thenReturn(Flux.<io.agentscope.core.event.AgentEvent>just(delta("part-1 "), delta("part-2")));
        ChannelDispatcher d = dispatcherWithAgent();
        RecordingStream stream = new RecordingStream();
        stream.updateFailure = new RuntimeException("platform down");

        d.dispatch(Map.of(), msg("hello"), (to, reply) -> {}, stream);

        assertEquals("part-1 part-2", stream.completed);
    }

    @Test
    void oneShotReplyUnchangedWithoutStream() {
        ChannelDispatcher d = dispatcherWithoutModel();
        List<String> replies = new ArrayList<>();

        d.dispatch(Map.of(), msg("hi"), (to, reply) -> replies.add(reply), null);

        assertEquals("当前未配置可用的模型，无法回复。请在控制台设置中配置模型。", replies.get(0));
        assertNull(replies.size() > 1 ? replies.get(1) : null);
    }
}
