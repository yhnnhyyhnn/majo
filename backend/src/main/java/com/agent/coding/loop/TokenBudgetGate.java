package com.agent.coding.loop;

/**
 * Stop a loop when configured token limits are reached, ported from
 * qwenpaw loop/gates/limits.py TokenBudgetGate. Priority 20.
 */
public class TokenBudgetGate extends LoopGate {

    private static final class TokenState {
        long promptTokens;
        long completionTokens;
        int lastIteration = -1;
    }

    private final long maxTotal;

    public TokenBudgetGate(long maxTotalTokens) {
        this.maxTotal = maxTotalTokens > 0 ? maxTotalTokens : 120_000;
    }

    @Override
    public String name() {
        return "token-budget";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public StopHandlerResult check(LoopContext ctx) {
        TokenState state = (TokenState) state();
        if (state == null) {
            state = new TokenState();
            activate(state);
        }
        if (state.lastIteration != ctx.iteration) {
            state.promptTokens += (long) (ctx.totalTokens * 0.6);
            state.completionTokens += (long) (ctx.totalTokens * 0.4);
            state.lastIteration = ctx.iteration;
        }
        long used = state.promptTokens + state.completionTokens;
        if (used >= maxTotal) {
            return StopHandlerResult.terminate(
                    "Token budget reached (" + maxTotal + ")");
        }
        return StopHandlerResult.bypass();
    }

    @Override
    public void resetTurn() {
        TokenState state = (TokenState) state();
        if (state != null) {
            state.promptTokens = 0;
            state.completionTokens = 0;
            state.lastIteration = -1;
        }
    }
}
