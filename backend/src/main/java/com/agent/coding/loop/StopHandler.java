package com.agent.coding.loop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Universal stop handler with composable gates, ported from qwenpaw
 * loop/gates/handler.py StopHandler.
 *
 * TERMINATE - agent stops immediately.
 * INTERRUPT_AND_CONTINUE - inject continuation, keep going.
 * BYPASS / null - gate idle, skip.
 * No gates or all BYPASS - TERMINATE.
 */
public class StopHandler {

    private final List<StopGate> gates = new ArrayList<>();

    public void register(StopGate gate) {
        gates.add(gate);
        gates.sort(Comparator.comparingInt(StopGate::priority));
    }

    public void unregister(String name) {
        gates.removeIf(g -> name.equals(g.name()));
    }

    public void replace(List<StopGate> newGates) {
        gates.clear();
        gates.addAll(newGates);
        gates.sort(Comparator.comparingInt(StopGate::priority));
    }

    public List<StopGate> gates() {
        return List.copyOf(gates);
    }

    public void resetTurn() {
        for (StopGate gate : gates) {
            try {
                gate.resetTurn();
            } catch (Exception ignored) {
            }
        }
    }

    public void resetSession() {
        for (StopGate gate : gates) {
            try {
                gate.resetSession();
            } catch (Exception ignored) {
            }
        }
    }

    /** Run all gates in priority order; first decisive result wins. */
    public StopHandlerResult run(LoopContext ctx) {
        if (gates.isEmpty()) {
            return StopHandlerResult.terminate("no gates");
        }
        for (StopGate gate : gates) {
            StopHandlerResult result;
            try {
                result = gate.check(ctx);
            } catch (Exception e) {
                continue;
            }
            if (result == null || result.action == StopAction.BYPASS) {
                continue;
            }
            if (result.action == StopAction.TERMINATE
                    || result.action == StopAction.INTERRUPT_AND_CONTINUE) {
                return result;
            }
        }
        return StopHandlerResult.terminate("all gates bypassed");
    }
}
