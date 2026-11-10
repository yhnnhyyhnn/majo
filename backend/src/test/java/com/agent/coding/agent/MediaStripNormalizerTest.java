package com.agent.coding.agent;

import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.service.ModelRoutingService;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Session-scoped media strip in the request normalizer (#8010 port): a
 * session whose provider rejected a media payload has its outgoing requests
 * stripped even on multimodal-capable models, while an unarmed session on
 * the same agent keeps its media. Invoked through the middleware-facing
 * normalize() entry (ADR-0022).
 */
class MediaStripNormalizerTest {

    private final ModelRoutingService routing = Mockito.mock(ModelRoutingService.class);
    private final ProviderModelRepository repo = Mockito.mock(ProviderModelRepository.class);
    private final com.agent.coding.memory.MemoryBackendRegistry memory =
            Mockito.mock(com.agent.coding.memory.MemoryBackendRegistry.class);
    private final ModelRequestNormalizerHook normalizer =
            new ModelRequestNormalizerHook(routing, repo, memory);

    @AfterEach
    void cleanup() {
        MediaPayloadRecovery.resetArmed();
    }

    @Test
    void armedSessionStripsRequestMediaDespiteMultimodalCapability() {
        // resolveEffectiveModel → null: capability unknown → fail open
        // (multimodal), so only the armed strip can remove the image.
        when(routing.resolveEffectiveModel(any())).thenReturn(null);
        MediaPayloadRecovery.armStrip("s-1");

        List<Msg> out = normalizer.normalize("s-1", "default", List.of(
                userMsgWithImage(),
                Msg.builder().name("user").role(MsgRole.USER)
                        .content(TextBlock.builder().text("describe it").build()).build()));

        boolean anyImage = out.stream()
                .flatMap(m -> m.getContent().stream())
                .anyMatch(b -> b instanceof ImageBlock);
        assertTrue(!anyImage, "armed session must not send image blocks");
    }

    @Test
    void unarmedSessionKeepsRequestMedia() {
        when(routing.resolveEffectiveModel(any())).thenReturn(null);

        List<Msg> out = normalizer.normalize("s-2", "default", List.of(userMsgWithImage()));

        boolean anyImage = out.stream()
                .flatMap(m -> m.getContent().stream())
                .anyMatch(b -> b instanceof ImageBlock);
        assertTrue(anyImage, "unarmed session keeps media");
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
