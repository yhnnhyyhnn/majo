package com.agent.coding.loop;

import java.util.List;

/**
 * Completion-signal gate for goal-style loops, adapted from the original
 * loop/gates/completion.py CompletionRubricGate. Instead of an LLM rubric
 * evaluation, checks whether the agent's final text contains a completion
 * signal; when absent, injects a continuation to keep working toward the
 * goal. Priority 40 (runs last).
 */
public class CompletionGate extends LoopGate {

    private static final List<String> DEFAULT_SIGNALS = List.of(
            "COMPLETED", "DONE", "FINISHED", "complete", "done", "finished");

    private final List<String> signals;
    private final int maxEvaluations;

    public CompletionGate(List<String> signals, int maxEvaluations) {
        this.signals = signals == null || signals.isEmpty() ? DEFAULT_SIGNALS : signals;
        this.maxEvaluations = maxEvaluations > 0 ? maxEvaluations : 3;
    }

    @Override
    public String name() {
        return "completion";
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public StopHandlerResult check(LoopContext ctx) {
        if (!ctx.hasToolCalls && !ctx.finalText.isBlank()) {
            boolean complete = false;
            for (String signal : signals) {
                if (ctx.finalText.contains(signal)) {
                    complete = true;
                    break;
                }
            }
            if (complete) {
                return StopHandlerResult.terminate("Completion signal detected");
            }
            if (ctx.iteration >= maxEvaluations) {
                return StopHandlerResult.terminate(
                        "Max rubric evaluations reached (" + maxEvaluations + ")");
            }
            return StopHandlerResult.continueWith(
                    "Continue working toward the goal. The task is not yet complete "
                            + "because no completion signal was found in the last response.",
                    "goal not yet complete");
        }
        return StopHandlerResult.bypass();
    }
}
