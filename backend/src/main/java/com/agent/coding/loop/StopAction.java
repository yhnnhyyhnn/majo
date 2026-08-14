package com.agent.coding.loop;

/**
 * Gate decisions for the agent loop, ported from qwenpaw loop/gates/base.py.
 */
public enum StopAction {
    /** Gate has no opinion, skip. */
    BYPASS,
    /** Interrupt current pattern, inject a prompt, then keep the loop going. */
    INTERRUPT_AND_CONTINUE,
    /** End the agent loop immediately. */
    TERMINATE
}
