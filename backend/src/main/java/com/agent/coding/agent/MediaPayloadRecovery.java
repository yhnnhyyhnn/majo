package com.agent.coding.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot recovery from media-payload rejections, ported from QwenPaw
 * (#8010): when the provider refuses the media bytes themselves — a corrupt
 * image, an unsupported container, dimensions past the provider's limit —
 * the offending block replays from stored context on every later turn, so
 * the same 400 returns until the media is removed and the session never
 * heals. Recovery retries the turn once with media stripped from the
 * request only; stored history keeps its blocks, and no model-wide
 * capability loss is inferred (the bytes were refused, not the modality).
 *
 * <p>After a payload rejection the strip flag stays armed for the agent:
 * later turns also go out media-free (request-only), because the poisoned
 * block is still in history.
 */
public final class MediaPayloadRecovery {

    private static final Logger log = LoggerFactory.getLogger(MediaPayloadRecovery.class);

    /**
     * Signals that the provider refused this media payload. They say nothing
     * about the model, so recovery must never learn them as a capability
     * loss. Deliberately excludes context/token overflow phrasing — that is
     * {@link OverflowRecovery}'s domain.
     */
    private static final List<String> PAYLOAD_SIGNALS = List.of(
            "corrupt", "decode", "invalid image", "invalid media",
            "invalid audio", "invalid video", "mime",
            "uploaded an unsupported image", "unsupported image",
            "unsupported media", "unsupported audio", "unsupported video",
            "unsupported image format", "unsupported image dimensions",
            "unsupported dimensions", "image too large", "image is too large",
            "image dimensions", "image is not valid", "image is invalid");

    /** A payload rejection only counts when the error names the media. */
    private static final Set<String> MEDIA_NOUNS =
            Set.of("image", "audio", "video", "media", "picture", "photo");

    /** Content-safety refusals mention the media but are not payload faults. */
    private static final List<String> SAFETY_SIGNALS = List.of(
            "content policy", "content moderation", "content_policy",
            "safety system", "flagged as sensitive", "violat");

    /** Agents whose outgoing requests strip media (stays armed for the agent). */
    private static final Set<String> STRIP_ARMED = ConcurrentHashMap.newKeySet();

    private MediaPayloadRecovery() {}

    /**
     * Wrap one agent turn with media-payload recovery. Mirrors
     * {@link OverflowRecovery#recover}: a stream that already emitted
     * meaningful output is never replayed, and the turn is retried at most
     * once.
     */
    public static Flux<AgentEvent> recover(Flux<AgentEvent> source, HarnessAgent agent,
                                           UserMessage prompt, RuntimeContext ctx) {
        AtomicBoolean meaningful = new AtomicBoolean(false);
        AtomicBoolean retried = new AtomicBoolean(false);
        return source
            .doOnNext(event -> {
                if (event.getClass().getSimpleName().startsWith("TextBlock")) {
                    meaningful.set(true);
                }
            })
            .onErrorResume(err -> {
                if (meaningful.get() || !isMediaPayloadRejection(err)) {
                    return Flux.error(err);
                }
                if (!retried.compareAndSet(false, true)) {
                    return Flux.error(err);
                }
                log.warn("[media] provider rejected the media payload ({}); "
                        + "retrying without media — history unchanged",
                        rootMessage(err));
                armStrip(agentNameOf(agent));
                return agent.streamEvents(prompt, ctx);
            });
    }

    /** Arm request-only media stripping for the agent (sticky until restart). */
    public static void armStrip(String agentName) {
        STRIP_ARMED.add(agentName);
    }

    /** Whether the agent's outgoing requests must strip media blocks. */
    public static boolean isStripArmed(String agentName) {
        return STRIP_ARMED.contains(agentName);
    }

    /** Test hook: clear the armed set. */
    static void resetArmed() {
        STRIP_ARMED.clear();
    }

    static String agentNameOf(HarnessAgent agent) {
        try {
            String name = agent.getName();
            return name == null ? "default" : name;
        } catch (Exception e) {
            return "default";
        }
    }

    /**
     * Whether the provider error rejects this media payload: it names a
     * medium and a payload-level fault, and is not a content-safety
     * refusal.
     */
    static boolean isMediaPayloadRejection(Throwable err) {
        Throwable t = err;
        int depth = 0;
        while (t != null && depth < 6) {
            String message = t.getMessage();
            if (message != null) {
                String normalized = " " + message.toLowerCase().replaceAll("\\s+", " ") + " ";
                if (containsAny(normalized, SAFETY_SIGNALS)) {
                    return false;
                }
                if (containsAny(normalized, MEDIA_NOUNS)
                        && containsAny(normalized, PAYLOAD_SIGNALS)) {
                    return true;
                }
            }
            t = t.getCause();
            depth++;
        }
        return false;
    }

    private static boolean containsAny(String haystack, Iterable<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? err.getClass().getSimpleName() : t.getMessage();
    }
}
