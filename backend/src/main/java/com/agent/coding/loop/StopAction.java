package com.agent.coding.loop;

/**
 * Gate decisions for the agent loop,.
 */
public enum StopAction {
    /** Gate has no opinion, skip. */
    BYPASS,
    /** Interrupt current pattern, inject a prompt, then keep the loop going. */
    INTERRUPT_AND_CONTINUE,
    /** End the agent loop immediately. */
    TERMINATE
}
