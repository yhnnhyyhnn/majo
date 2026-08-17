package com.agent.coding.loop;

/**
 * Evaluation context passed to every gate on each loop iteration, mirroring
 * the ctx dict the original builds in loop/gates/runner.py.
 */
public class LoopContext {

    public final int iteration;
    public final boolean hasToolCalls;
    public final String finalText;
    public final ToolCallRecord lastToolCall;
    public final int totalTokens;

    /** One completed tool call, used by repetition-detection gates. */
    public record ToolCallRecord(String toolName, String argsHash) {
    }

    public LoopContext(int iteration, boolean hasToolCalls, String finalText,
                       ToolCallRecord lastToolCall, int totalTokens) {
        this.iteration = iteration;
        this.hasToolCalls = hasToolCalls;
        this.finalText = finalText == null ? "" : finalText;
        this.lastToolCall = lastToolCall;
        this.totalTokens = totalTokens;
    }

    public static LoopContext forIteration(int iteration, boolean hasToolCalls, String finalText) {
        return new LoopContext(iteration, hasToolCalls, finalText, null, 0);
    }
}
