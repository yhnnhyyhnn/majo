package com.agent.coding.loop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the loop gate system (ported from qwenpaw loop/gates): iteration
 * limit, timeout, doom-loop repetition detection, completion gate and the
 * StopHandler orchestration.
 */
class LoopGateTest {

    @Test
    void iterationGateTerminatesAtLimit() {
        IterationGate gate = new IterationGate(3);
        LoopGate.setCurrentSession("s1");
        try {
            assertTrue(gate.check(LoopContext.forIteration(0, false, "")).action == StopAction.BYPASS);
            assertTrue(gate.check(LoopContext.forIteration(1, false, "")).action == StopAction.BYPASS);
            assertTrue(gate.check(LoopContext.forIteration(2, false, "")).action == StopAction.BYPASS);
            StopHandlerResult r = gate.check(LoopContext.forIteration(3, false, ""));
            assertTrue(r.isTerminate());
            assertTrue(r.reason.contains("Iteration limit"));
        } finally {
            LoopGate.clearCurrentSession();
        }
    }

    @Test
    void iterationStateIsolatedPerSession() {
        IterationGate gate = new IterationGate(1);
        LoopGate.setCurrentSession("sA");
        try {
            assertTrue(gate.check(LoopContext.forIteration(0, false, "")).action == StopAction.BYPASS);
        } finally {
            LoopGate.clearCurrentSession();
        }
        LoopGate.setCurrentSession("sB");
        try {
            assertTrue(gate.check(LoopContext.forIteration(0, false, "")).action == StopAction.BYPASS);
        } finally {
            LoopGate.clearCurrentSession();
        }
    }

    @Test
    void emptyHandlerTerminates() {
        StopHandler handler = new StopHandler();
        StopHandlerResult r = handler.run(LoopContext.forIteration(0, false, ""));
        assertTrue(r.isTerminate());
    }

    @Test
    void doomLoopBypassesBelowWindow() {
        DoomLoopGate gate = DoomLoopGate.defaultConfig();
        LoopGate.setCurrentSession("d1");
        try {
            StopHandlerResult r = gate.check(LoopContext.forIteration(0, true, ""));
            assertTrue(r.action == StopAction.BYPASS);
        } finally {
            LoopGate.clearCurrentSession();
        }
    }

    @Test
    void doomLoopWarnsThenStops() {
        DoomLoopGate gate = DoomLoopGate.defaultConfig();
        StopHandler handler = new StopHandler();
        handler.register(gate);
        LoopGate.setCurrentSession("d2");
        try {
            String hash = DoomLoopGate.hashArgs("{\"path\":\"a.txt\"}");
            for (int i = 0; i < 3; i++) {
                gate.record("read_file", hash);
            }
            StopHandlerResult r = handler.run(LoopContext.forIteration(1, true, ""));
            assertTrue(r.isContinue());
            assertTrue(r.continuationMessage.contains("WARNING"));
            gate.record("read_file", hash);
            StopHandlerResult r2 = handler.run(LoopContext.forIteration(2, true, ""));
            assertTrue(r2.isTerminate());
        } finally {
            LoopGate.clearCurrentSession();
        }
    }

    @Test
    void timeoutGateTerminatesAfterElapsed() {
        StopHandler handler = new StopHandler();
        handler.register(new TimeoutGate(0.001));
        LoopGate.setCurrentSession("t1");
        try {
            Thread.sleep(5);
            StopHandlerResult r = handler.run(LoopContext.forIteration(0, false, ""));
            assertTrue(r.isTerminate());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            LoopGate.clearCurrentSession();
        }
    }

    @Test
    void completionGateContinuesUntilSignal() {
        StopHandler handler = new StopHandler();
        handler.register(new CompletionGate(null, 5));
        LoopGate.setCurrentSession("c1");
        try {
            StopHandlerResult r = handler.run(new LoopContext(0, false, "Working on it...", null, 0));
            assertTrue(r.isContinue());
            StopHandlerResult r2 = handler.run(new LoopContext(1, false, "Task COMPLETED", null, 0));
            assertTrue(r2.isTerminate());
        } finally {
            LoopGate.clearCurrentSession();
        }
    }

    @Test
    void tokenBudgetStopsAtLimit() {
        StopHandler handler = new StopHandler();
        handler.register(new TokenBudgetGate(10));
        LoopGate.setCurrentSession("b1");
        try {
            StopHandlerResult r = handler.run(new LoopContext(0, false, "x", null, 10));
            assertTrue(r.isTerminate());
        } finally {
            LoopGate.clearCurrentSession();
        }
    }

    @Test
    void gateCatalogDescribesSevenGates() {
        List<Map<String, Object>> catalog = GateCatalog.builtin().describe();
        assertEquals(7, catalog.size());
        Map<String, Object> first = catalog.get(0);
        assertEquals("iteration", first.get("type"));
        assertEquals("limits", first.get("category"));
    }

    @Test
    void gateCatalogCreatesIterationGate() {
        StopGate gate = GateCatalog.builtin().create("iteration", Map.of("max_iterations", 5));
        assertInstanceOf(IterationGate.class, gate);
    }

    @Test
    void gateCatalogRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> GateCatalog.builtin().create("nonexistent", Map.of()));
    }

    @Test
    void priorityOrderRunsIterationFirst() {
        StopHandler handler = new StopHandler();
        handler.register(new CompletionGate(null, 5));
        handler.register(new IterationGate(5));
        LoopGate.setCurrentSession("p1");
        try {
            StopHandlerResult r = handler.run(new LoopContext(5, false, "COMPLETED", null, 0));
            assertTrue(r.isTerminate());
            assertTrue(r.reason.contains("Iteration limit"));
        } finally {
            LoopGate.clearCurrentSession();
        }
    }
}
