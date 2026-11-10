package com.agent.coding.agent;

import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.service.ModelRoutingService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Session-scoped media strip in the request normalizer (#8010 port): a
 * session whose provider rejected a media payload has its outgoing requests
 * stripped even on multimodal-capable models, while an unarmed session on
 * the same agent keeps its media. Session id resolves from the event's
 * agent state (agentscope 2.1.0 removed RuntimeContextAware).
 */
class MediaStripNormalizerTest {

    private final ModelRoutingService routing = mock(ModelRoutingService.class);
    private final ProviderModelRepository repo = mock(ProviderModelRepository.class);
    private final com.agent.coding.memory.MemoryBackendRegistry memory =
            mock(com.agent.coding.memory.MemoryBackendRegistry.class);
    private final ModelRequestNormalizerHook hook =
            new ModelRequestNormalizerHook(routing, repo, memory);

    @AfterEach
    void cleanup() {
        MediaPayloadRecovery.resetArmed();
    }

    private PreReasoningEvent eventForSession(String sessionId, List<Msg> messages) {
        AgentState state = mock(AgentState.class);
        when(state.getSessionId()).thenReturn(sessionId);
        Agent agent = mock(Agent.class);
        when(agent.getAgentState()).thenReturn(state);
        PreReasoningEvent pre = mock(PreReasoningEvent.class);
        when(pre.getAgent()).thenReturn(agent);
        when(pre.getInputMessages()).thenReturn(messages);
        return pre;
    }

    @Test
    void armedSessionStripsRequestMediaDespiteMultimodalCapability() {
        // resolveEffectiveModel → null: capability unknown → fail open
        // (multimodal), so only the armed strip can remove the image.
        when(routing.resolveEffectiveModel(any())).thenReturn(null);
        MediaPayloadRecovery.armStrip("s-1");

        PreReasoningEvent pre = eventForSession("s-1", new ArrayList<>(List.of(
                userMsgWithImage(),
                Msg.builder().name("user").role(MsgRole.USER)
                        .content(TextBlock.builder().text("describe it").build()).build())));

        hook.onEvent(pre);

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(pre).setInputMessages(captor.capture());
        boolean anyImage = captor.getValue().stream()
                .flatMap(m -> m.getContent().stream())
                .anyMatch(b -> b instanceof ImageBlock);
        assertTrue(!anyImage, "armed session must not send image blocks");
    }

    @Test
    void unarmedSessionKeepsRequestMedia() {
        when(routing.resolveEffectiveModel(any())).thenReturn(null);

        PreReasoningEvent pre = eventForSession("s-2",
                new ArrayList<>(List.of(userMsgWithImage())));

        hook.onEvent(pre);

        verify(pre, never()).setInputMessages(any());
    }

    private static Msg userMsgWithImage() {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(List.of(
                        TextBlock.builder().text("look:").build(),
                        ImageBlock.builder()
                                .source(new Base64Source("image/png", "iVBOR"))
                                .build()))
                .build();
    }
}
