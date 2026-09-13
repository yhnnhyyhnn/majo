package com.agent.coding.agent;

import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.service.ModelRoutingService;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Strips media blocks (image / audio / video / PDF data) from the model
 * request when the active model does not support multimodal input. Ported
 * from QwenPaw's {@code message_request_normalizer}, including the
 * application/pdf handling (#7621): a text-only model receiving media
 * blocks fails the whole request on most providers.
 *
 * <p>Runs on PRE_REASONING so only the outgoing request is affected — the
 * stored conversation history keeps its media blocks. When the model's
 * capability cannot be resolved the hook fails open (no stripping).
 */
@Component
public class ModelRequestNormalizerHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ModelRequestNormalizerHook.class);

    /** Mirrors QwenPaw MEDIA_UNSUPPORTED_PLACEHOLDER. */
    public static final String MEDIA_UNSUPPORTED_PLACEHOLDER =
            "[Media content removed - model does not support this media type]";

    /** Document MIME types treated as media alongside image/audio/video. */
    private static final List<String> DOCUMENT_MIME_TYPES = List.of("application/pdf");

    private final ModelRoutingService modelRoutingService;
    private final ProviderModelRepository providerModelRepo;

    public ModelRequestNormalizerHook(ModelRoutingService modelRoutingService,
                                      ProviderModelRepository providerModelRepo) {
        this.modelRoutingService = modelRoutingService;
        this.providerModelRepo = providerModelRepo;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreReasoningEvent pre)) {
            return Mono.just(event);
        }
        try {
            String agentId = agentIdOf(pre);
            boolean multimodal;
            try {
                multimodal = supportsMultimodal(agentId);
            } catch (Exception e) {
                multimodal = true; // capability unknown → fail open
            }
            List<Msg> msgs = pre.getInputMessages();
            if (msgs == null || msgs.isEmpty()) {
                return Mono.just(event);
            }

            // 1) Bound oversized tool results (always; request copies only).
            List<Msg> rebuilt = new ArrayList<>(msgs.size());
            boolean changed = false;
            for (Msg msg : msgs) {
                Msg bounded = ContextCompactor.boundToolResults(msg);
                if (bounded != msg) {
                    changed = true;
                }
                rebuilt.add(bounded);
            }

            // 2) Media stripping for text-only models.
            if (!multimodal) {
                List<Msg> stripped = new ArrayList<>(rebuilt.size());
                for (Msg msg : rebuilt) {
                    Msg s = stripMediaBlocks(msg);
                    if (s != msg) {
                        changed = true;
                    }
                    stripped.add(s);
                }
                rebuilt = stripped;
            }

            // 3) Context-pressure compaction: fold old thinking and images
            //    when the request estimate exceeds the pressure threshold
            //    (QwenPaw #7521 thinking fold + #6456 visual compact).
            Integer window = resolveContextWindow(agentId);
            if (window != null && window > 0) {
                long threshold = (long) (window * ContextCompactor.PRESSURE_RATIO);
                long estimated = ContextCompactor.estimateTokens(rebuilt);
                if (estimated > threshold) {
                    List<Msg> folded = ContextCompactor.foldForPressure(rebuilt);
                    if (folded != rebuilt) {
                        changed = true;
                        rebuilt = folded;
                        log.info("[normalizer] context pressure: ~{} tokens > {} "
                                        + "(70% of {} window); folded old thinking/images",
                                estimated, threshold, window);
                    }
                }
            }

            if (changed) {
                pre.setInputMessages(rebuilt);
            }
        } catch (Exception e) {
            // Fail open — a broken compaction must never break a call.
            log.debug("[normalizer] request normalization skipped: {}", e.getMessage());
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 5;
    }

    // ── Stripping (request copies only; history untouched) ───────────

    private Msg stripMediaBlocks(Msg msg) {
        List<ContentBlock> content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return msg;
        }
        List<ContentBlock> out = new ArrayList<>(content.size());
        boolean changed = false;
        for (ContentBlock block : content) {
            if (block instanceof ToolResultBlock trb) {
                ToolResultBlock filtered = filterToolResult(trb);
                if (filtered != trb) {
                    changed = true;
                }
                out.add(filtered);
                continue;
            }
            if (isMediaBlock(block)) {
                changed = true;
                continue;
            }
            out.add(block);
        }
        if (!changed) {
            return msg;
        }
        if (out.isEmpty()) {
            out.add(TextBlock.builder().text(MEDIA_UNSUPPORTED_PLACEHOLDER).build());
        }
        return msg.withContent(out);
    }

    /** Tool results carry nested media in their output blocks. */
    private ToolResultBlock filterToolResult(ToolResultBlock trb) {
        List<ContentBlock> output = trb.getOutput();
        if (output == null || output.isEmpty()) {
            return trb;
        }
        List<ContentBlock> filtered = new ArrayList<>(output.size());
        boolean changed = false;
        for (ContentBlock block : output) {
            if (isMediaBlock(block)) {
                changed = true;
                continue;
            }
            filtered.add(block);
        }
        if (!changed) {
            return trb;
        }
        if (filtered.isEmpty()) {
            filtered.add(TextBlock.builder().text(MEDIA_UNSUPPORTED_PLACEHOLDER).build());
        }
        return ToolResultBlock.builder()
                .id(trb.getId())
                .name(trb.getName())
                .output(filtered)
                .metadata(trb.getMetadata())
                .state(trb.getState())
                .build();
    }

    // ── Media detection (incl. application/pdf, #7621) ───────────────

    private static boolean isMediaBlock(ContentBlock block) {
        if (block instanceof ImageBlock || block instanceof AudioBlock || block instanceof VideoBlock) {
            return true;
        }
        if (block instanceof DataBlock data) {
            String mt = mediaTypeOf(data.getSource());
            return mt.startsWith("image/") || mt.startsWith("audio/")
                    || mt.startsWith("video/") || DOCUMENT_MIME_TYPES.contains(mt);
        }
        return false;
    }

    private static String mediaTypeOf(io.agentscope.core.message.Source source) {
        if (source instanceof Base64Source b64) {
            String mt = b64.getMediaType();
            return mt == null ? "" : mt.toLowerCase();
        }
        if (source instanceof URLSource url) {
            String mt = url.getMimeType();
            return mt == null ? "" : mt.toLowerCase();
        }
        return "";
    }

    // ── Capability lookup ────────────────────────────────────────────

    private boolean supportsMultimodal(String agentId) {
        var slot = modelRoutingService.resolveEffectiveModel(agentId);
        if (slot == null || !slot.hasBoth()) {
            return true; // routing unknown → fail open
        }
        var entity = providerModelRepo
                .findByProviderIdAndModelId(slot.providerId(), slot.modelId())
                .orElse(null);
        if (entity != null) {
            return Boolean.TRUE.equals(entity.getSupportsMultimodal())
                    || Boolean.TRUE.equals(entity.getSupportsImage())
                    || Boolean.TRUE.equals(entity.getSupportsVideo());
        }
        // No metadata row — fall back to the same model-id heuristic as
        // /probe-multimodal (models seeded without discovery).
        return heuristicMultimodal(slot.modelId());
    }

    /** Resolve the provider-resolved context window for pressure thresholds. */
    private Integer resolveContextWindow(String agentId) {
        var slot = modelRoutingService.resolveEffectiveModel(agentId);
        if (slot == null || !slot.hasBoth()) {
            return null;
        }
        return modelRoutingService.getContextSize(slot.providerId(), slot.modelId());
    }

    private static boolean heuristicMultimodal(String modelId) {
        String lower = modelId == null ? "" : modelId.toLowerCase();
        return lower.contains("vision") || lower.contains("vl") || lower.contains("omni")
                || lower.contains("gpt-4o") || lower.contains("gemini");
    }

    private static String agentIdOf(PreReasoningEvent event) {
        try {
            if (event.getAgent() != null && event.getAgent().getName() != null) {
                return event.getAgent().getName();
            }
        } catch (Exception ignored) {
        }
        return "default";
    }
}
