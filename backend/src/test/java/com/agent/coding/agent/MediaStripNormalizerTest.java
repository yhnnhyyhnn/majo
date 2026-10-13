package com.agent.coding.agent;

import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.service.ModelRoutingService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
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
 * the same agent keeps its media.
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
        hook.setRuntimeContext(null);
    }

    @Test
    void armedSessionStripsRequestMediaDespiteMultimodalCapability() {
        // resolveEffectiveModel → null: capability unknown → fail open
        // (multimodal), so only the armed strip can remove the image.
        when(routing.resolveEffectiveModel(any())).thenReturn(null);
        hook.setRuntimeContext(RuntimeContext.builder().sessionId("s-1").build());
        MediaPayloadRecovery.armStrip("s-1");

        PreReasoningEvent pre = mock(PreReasoningEvent.class);
        when(pre.getInputMessages()).thenReturn(new ArrayList<>(List.of(
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
        hook.setRuntimeContext(RuntimeContext.builder().sessionId("s-2").build());

        PreReasoningEvent pre = mock(PreReasoningEvent.class);
        when(pre.getInputMessages()).thenReturn(new ArrayList<>(List.of(userMsgWithImage())));

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
