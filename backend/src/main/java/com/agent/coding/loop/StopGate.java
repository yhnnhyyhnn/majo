package com.agent.coding.loop;

/**
 * Abstract base class for all stop condition gates
 * loop/gates/base.py StopGate.
 *
 * Lifecycle per evaluation (driven by StopHandler):
 * 1. check(ctx) returns an action
 * 2. TERMINATE - stop immediately
 * 3. INTERRUPT_AND_CONTINUE - handler calls buildContinuation() and injects
 *    the message as a new user turn
 * 4. BYPASS - gate idle, no action
 */
public abstract class StopGate {

    /** Unique gate identifier (used for unregister). */
    public abstract String name();

    /** Evaluation order. Lower = earlier. */
    public int priority() {
        return 100;
    }

    /** Evaluate one stop condition. */
    public abstract StopHandlerResult check(LoopContext ctx);

    /** Text to inject as a user turn when check() returns INTERRUPT_AND_CONTINUE. */
    public String buildContinuation() {
        return "";
    }

    /** Reset turn-local state without ending mode sessions. */
    public void resetTurn() {
    }

    /** Remove current-session state. */
    public void resetSession() {
    }
}
