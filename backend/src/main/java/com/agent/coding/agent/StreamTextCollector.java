package com.agent.coding.agent;

import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;

/**
 * Accumulates assistant text and thinking deltas from an agent event stream.
 *
 * <p>Background runners (channel dispatch, subagent) need the same
 * two-buffer collection; each used to carry its own reflective
 * class-name switch over the harness event types. The delta events are
 * stable API, so this class binds them directly and is the single
 * implementation — other events (block start/end, tool calls) are ignored.
 */
public final class StreamTextCollector {

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();

    /** Collect TextBlockDeltaEvent / ThinkingBlockDeltaEvent deltas. */
    public void accept(Object event) {
        if (event instanceof TextBlockDeltaEvent e) {
            text.append(e.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent e) {
            thinking.append(e.getDelta());
        }
    }

    /** Collected text deltas, possibly empty. */
    public String text() {
        return text.toString();
    }

    /** Collected thinking deltas, possibly empty. */
    public String thinking() {
        return thinking.toString();
    }
}
