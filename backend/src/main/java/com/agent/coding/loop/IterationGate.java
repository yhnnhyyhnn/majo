package com.agent.coding.loop;

/**
 * Hard iteration cap for the outer loop
 * loop/gates/iteration.py IterationGate. Priority 10 (runs early).
 */
public class IterationGate extends LoopGate {

    private static final class IterState {
        int iteration;
        final int maxIterations;

        IterState(int maxIterations) {
            this.maxIterations = maxIterations;
        }
    }

    private final int defaultMax;

    public IterationGate(int maxIterations) {
        this.defaultMax = maxIterations > 0 ? maxIterations : 20;
    }

    @Override
    public String name() {
        return "iteration";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public StopHandlerResult check(LoopContext ctx) {
        IterState state = (IterState) state();
        if (state == null) {
            state = new IterState(defaultMax);
            activate(state);
        }
        if (ctx.iteration >= state.maxIterations) {
            return StopHandlerResult.terminate("Iteration limit reached (" + state.maxIterations + ")");
        }
        return StopHandlerResult.bypass();
    }

    @Override
    public void resetTurn() {
        IterState state = (IterState) state();
        if (state != null) {
            state.iteration = 0;
        }
    }
}
