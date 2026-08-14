package com.agent.coding.loop;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests LoopCompiler: compiling a user-configured custom loop mode (the JSON
 * shape from running.loop.custom_modes) into a StopHandler with the right
 * gates, plus exclusive-group validation.
 */
class LoopCompilerTest {

    private static Map<String, Object> gate(String type, boolean enabled, Map<String, Object> params) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", type);
        g.put("type", type);
        g.put("enabled", enabled);
        g.put("params", params == null ? Map.of() : params);
        return g;
    }

    @Test
    void compilesEnabledGatesOnly() {
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("id", "m1");
        mode.put("gates", List.of(
                gate("iteration", true, Map.of("max_iterations", 5)),
                gate("timeout", false, Map.of("max_seconds", 60)),
                gate("doom_loop", true, Map.of())));
        StopHandler handler = LoopCompiler.defaultCompiler().compile(mode);
        List<StopGate> gates = handler.gates();
        assertEquals(2, gates.size());
        assertTrue(gates.stream().anyMatch(g -> g instanceof IterationGate));
        assertTrue(gates.stream().anyMatch(g -> g instanceof DoomLoopGate));
    }

    @Test
    void emptyGatesProducesTerminateHandler() {
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("id", "m2");
        mode.put("gates", List.of());
        StopHandler handler = LoopCompiler.defaultCompiler().compile(mode);
        LoopGate.setCurrentSession("c1");
        try {
            StopHandlerResult r = handler.run(LoopContext.forIteration(0, false, ""));
            assertTrue(r.isTerminate());
        } finally {
            LoopGate.clearCurrentSession();
        }
    }

    @Test
    void rejectsUnknownGateType() {
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("id", "m3");
        mode.put("gates", List.of(gate("nonexistent", true, Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> LoopCompiler.defaultCompiler().compile(mode));
    }

    @Test
    void rejectsDuplicateExclusiveGroup() {
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("id", "m4");
        mode.put("gates", List.of(
                gate("qualitative_rubric", true, Map.of()),
                gate("completion_rubric", true, Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> LoopCompiler.defaultCompiler().compile(mode));
    }

    @Test
    void customGatesDriveLoopExecution() {
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("id", "m5");
        mode.put("gates", List.of(
                gate("iteration", true, Map.of("max_iterations", 2)),
                gate("completion_rubric", true, Map.of("completion_signal", "DONE"))));
        StopHandler handler = LoopCompiler.defaultCompiler().compile(mode);
        LoopGate.setCurrentSession("c2");
        try {
            StopHandlerResult r1 = handler.run(new LoopContext(0, false, "working...", null, 0));
            assertTrue(r1.isContinue());
            StopHandlerResult r2 = handler.run(new LoopContext(1, false, "all DONE", null, 0));
            assertTrue(r2.isTerminate());
        } finally {
            LoopGate.clearCurrentSession();
        }
    }
}
