package com.agent.coding.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot context-overflow recovery across model calls and stream
 * consumption. Ported from QwenPaw overflow_recovery (#7748): recovery
 * classifies the error, compacts context, and re-issues the turn exactly
 * once; a stream that already emitted meaningful output is never replayed,
 * and the recovery response is never wrapped for retry again.
 */
public final class OverflowRecovery {

    private static final Logger log = LoggerFactory.getLogger(OverflowRecovery.class);

    /** Substrings that identify a context-overflow failure across providers. */
    private static final List<String> OVERFLOW_MARKERS = List.of(
            "context length", "context_length", "maximum context", "context window",
            "too many tokens", "token limit", "prompt is too long", "request too large",
            "input length exceeds", "reduce the length", "overflow");

    private OverflowRecovery() {}

    /**
     * Wrap one agent turn with overflow recovery.
     *
     * @param source  the turn's event stream
     * @param agent   the running agent (its state context is compacted)
     * @param prompt  the user message of this turn
     * @param ctx     runtime context for the retry
     */
    public static Flux<AgentEvent> recover(Flux<AgentEvent> source, HarnessAgent agent,
                                           UserMessage prompt, RuntimeContext ctx) {
        AtomicBoolean meaningful = new AtomicBoolean(false);
        AtomicBoolean retried = new AtomicBoolean(false);
        return source
            .doOnNext(event -> {
                String type = event.getClass().getSimpleName();
                if (type.startsWith("TextBlock")) {
                    meaningful.set(true);
                }
            })
            .onErrorResume(err -> {
                if (meaningful.get()) {
                    log.warn("[overflow] stream already emitted meaningful output; not replaying ({})",
                            rootMessage(err));
                    return Flux.error(err);
                }
                if (!isOverflow(err)) {
                    return Flux.error(err);
                }
                if (!retried.compareAndSet(false, true)) {
                    return Flux.error(err);
                }
                log.warn("[overflow] context overflow detected ({}); compacting context and retrying once",
                        rootMessage(err));
                if (!compactContext(agent, prompt.getTextContent())) {
                    return Flux.error(err);
                }
                return agent.streamEvents(prompt, ctx);
            });
    }

    static boolean isOverflow(Throwable err) {
        Throwable t = err;
        int depth = 0;
        while (t != null && depth < 6) {
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                for (String marker : OVERFLOW_MARKERS) {
                    if (lower.contains(marker)) {
                        return true;
                    }
                }
            }
            t = t.getCause();
            depth++;
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

    /**
     * Compact the agent's stored context for a retry: drop the trailing user
     * message the failed attempt already appended (so the retry does not
     * duplicate it) and fold old thinking/images plus bound oversized tool
     * results.
     *
     * @return true when the context was rewritten and a retry makes sense
     */
    static boolean compactContext(HarnessAgent agent, String promptText) {
        try {
            if (agent.getAgentState() == null) {
                return false;
            }
            List<Msg> context = agent.getAgentState().contextMutable();
            if (context == null || context.isEmpty()) {
                return false;
            }
            List<Msg> current = new ArrayList<>(context);
            boolean changed = false;
            // Drop the user message the failed attempt already appended so
            // the retry does not send it twice.
            Msg last = current.get(current.size() - 1);
            if (last.getRole() == MsgRole.USER
                    && promptText != null
                    && promptText.equals(last.getTextContent())) {
                current.remove(current.size() - 1);
                changed = true;
            }
            List<Msg> rebuilt = new ArrayList<>(current.size());
            for (Msg msg : current) {
                Msg bounded = ContextCompactor.boundToolResults(msg);
                changed |= bounded != msg;
                rebuilt.add(bounded);
            }
            List<Msg> folded = ContextCompactor.foldForPressure(rebuilt);
            changed |= folded != rebuilt;
            if (!changed) {
                return false; // nothing to free — a retry would overflow again
            }
            context.clear();
            context.addAll(folded);
            return true;
        } catch (Exception e) {
            log.warn("[overflow] context compaction failed: {}", e.getMessage());
            return false;
        }
    }
}
