package com.agent.coding.channel;

/**
 * A live reply handle for channels that can update a delivered message in
 * place (Feishu interactive cards). The dispatcher streams accumulated
 * text into {@link #update} (throttled) and finishes with exactly one
 * {@link #complete} — success text, truncation note, or the error message.
 * Implementations must never throw: a failed platform update is logged and
 * dropped, the agent run itself is unaffected.
 */
public interface StreamingReply {

    /** Replace the rendered content with the accumulated text so far. */
    void update(String accumulatedText);

    /** Final content; called exactly once, after which the handle is dead. */
    void complete(String finalText);
}
