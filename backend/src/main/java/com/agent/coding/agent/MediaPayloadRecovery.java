package com.agent.coding.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>The strip flag is armed per <b>session</b>, not per agent: HarnessAgent
 * instances are rebuilt every turn and their names are shared across a
 * profile's chats, while the poisoned block lives in one session's
 * persisted history — keying by agent name would silently strip media from
 * every other chat of that agent. While armed, that session's outgoing
 * requests go out media-free (the poisoned block stays in history until the
 * chat is cleared).
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

    /** Bound the armed set: long-lived processes accumulate sessions. */
    private static final int MAX_ARMED_SESSIONS = 512;

    /** Sessions whose outgoing requests strip media (insertion-bounded). */
    private static final Set<String> STRIP_ARMED = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_ARMED_SESSIONS;
                }
            }));

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
                String sessionId = sessionKeyOf(ctx);
                log.warn("[media] provider rejected the media payload ({}); "
                        + "retrying without media for session {} — history unchanged",
                        rootMessage(err), sessionId);
                armStrip(sessionId);
                return agent.streamEvents(prompt, ctx);
            });
    }

    /** Arm request-only media stripping for the session (sticky while armed). */
    public static void armStrip(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return; // no session scope → nothing persistent to guard
        }
        synchronized (STRIP_ARMED) {
            // Re-insert so the bound evicts by last-armed order, not first.
            STRIP_ARMED.remove(sessionId);
            STRIP_ARMED.add(sessionId);
        }
    }

    /** Whether the session's outgoing requests must strip media blocks. */
    public static boolean isStripArmed(String sessionId) {
        return sessionId != null && STRIP_ARMED.contains(sessionId);
    }

    /** Test hook: clear the armed set. */
    static void resetArmed() {
        STRIP_ARMED.clear();
    }

    static String sessionKeyOf(RuntimeContext ctx) {
        try {
            return ctx == null ? null : ctx.getSessionId();
        } catch (Exception e) {
            return null;
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
