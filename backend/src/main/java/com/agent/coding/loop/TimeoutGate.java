package com.agent.coding.loop;

/**
 * Elapsed-time limit checked at loop boundaries
 * loop/gates/limits.py TimeoutGate. Priority 30.
 */
public class TimeoutGate extends LoopGate {

    private static final class TimeoutState {
        final long startNanos = System.nanoTime();
        final double maxSeconds;

        TimeoutState(double maxSeconds) {
            this.maxSeconds = maxSeconds;
        }
    }

    private final double defaultMaxSeconds;

    public TimeoutGate(double maxSeconds) {
        this.defaultMaxSeconds = maxSeconds > 0 ? maxSeconds : 1800.0;
    }

    @Override
    public String name() {
        return "timeout";
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public StopHandlerResult check(LoopContext ctx) {
        TimeoutState state = (TimeoutState) state();
        if (state == null) {
            state = new TimeoutState(defaultMaxSeconds);
            activate(state);
        }
        double elapsed = (System.nanoTime() - state.startNanos) / 1_000_000_000.0;
        if (elapsed >= state.maxSeconds) {
            return StopHandlerResult.terminate(
                    "Loop time limit reached (" + (long) state.maxSeconds + "s)");
        }
        return StopHandlerResult.bypass();
    }
}
