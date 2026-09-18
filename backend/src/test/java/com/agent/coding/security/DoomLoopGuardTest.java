package com.agent.coding.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doom-loop guard tests (QwenPaw doom_loop gate port): staged escalation on
 * identical repeated calls, reset on a differing call, and per-turn reset.
 */
class DoomLoopGuardTest {

    @Test
    void identicalCallsEscalateWarnThenStop() {
        DoomLoopGuard guard = new DoomLoopGuard();
        String first = guard.check("s1", "read_file", Map.of("path", "a.md"));
        assertNull(first, "first call allowed");
        assertNull(guard.check("s1", "read_file", Map.of("path", "a.md")), "second allowed");

        String warn = guard.check("s1", "read_file", Map.of("path", "a.md"));
        assertTrue(warn.contains("疑似死循环"), warn);
        String warnAgain = guard.check("s1", "read_file", Map.of("path", "a.md"));
        assertTrue(warnAgain.contains("疑似死循环"));

        // calls 5 → warn; call 6 → stop
        assertTrue(guard.check("s1", "read_file", Map.of("path", "a.md")).contains("疑似死循环"));
        String stop = guard.check("s1", "read_file", Map.of("path", "a.md"));
        assertTrue(stop.contains("死循环保护"), stop);
        assertTrue(guard.check("s1", "read_file", Map.of("path", "a.md")).contains("死循环保护"),
                "stays stopped while the pattern continues");
    }

    @Test
    void differingCallResetsThePattern() {
        DoomLoopGuard guard = new DoomLoopGuard();
        for (int i = 0; i < 2; i++) {
            guard.check("s1", "read_file", Map.of("path", "a.md"));
        }
        // A different call breaks the pattern instead of escalating.
        assertNull(guard.check("s1", "read_file", Map.of("path", "b.md")));
        // And the counter restarts from 1 for the new signature.
        assertNull(guard.check("s1", "read_file", Map.of("path", "b.md")));
        // Third identical b.md call → warn again (counter truly restarted).
        assertTrue(guard.check("s1", "read_file", Map.of("path", "b.md")).contains("疑似死循环"));
    }

    @Test
    void sameToolDifferentArgsIsNotALoop() {
        DoomLoopGuard guard = new DoomLoopGuard();
        for (int i = 0; i < 5; i++) {
            assertNull(guard.check("s1", "execute_command", Map.of("command", "cmd-" + i)));
        }
    }

    @Test
    void sessionsAreIsolated() {
        DoomLoopGuard guard = new DoomLoopGuard();
        for (int i = 0; i < 2; i++) {
            guard.check("a", "read_file", Map.of("path", "a.md"));
        }
        // Session b has its own counter — first call is fine.
        assertNull(guard.check("b", "read_file", Map.of("path", "a.md")));
    }

    @Test
    void resetTurnClearsSessionState() {
        DoomLoopGuard guard = new DoomLoopGuard();
        for (int i = 0; i < 2; i++) {
            guard.check("s1", "read_file", Map.of("path", "a.md"));
        }
        guard.reset("s1");
        assertNull(guard.check("s1", "read_file", Map.of("path", "a.md")),
                "counter restarts after turn reset");
    }

    @Test
    void argsHashIsStableAndInputSensitive() {
        String a1 = DoomLoopGuard.argsHash(Map.of("path", "a.md"));
        String a2 = DoomLoopGuard.argsHash(Map.of("path", "a.md"));
        String b = DoomLoopGuard.argsHash(Map.of("path", "b.md"));
        assertEquals(a1, a2);
        assertNotEquals(a1, b);
        // Large inputs hash their capped prefix deterministically.
        String big1 = DoomLoopGuard.argsHash(Map.of("content", "x".repeat(5000)));
        String big2 = DoomLoopGuard.argsHash(Map.of("content", "x".repeat(5000) + "tail"));
        assertEquals(big1, big2, "hash only sees the first 2048 chars");
    }

    @Test
    void nullSessionOrToolIsAlwaysAllowed() {
        DoomLoopGuard guard = new DoomLoopGuard();
        String nullSession = null;
        assertNull(guard.check(nullSession, "read_file", Map.of()));
        assertNull(guard.check("s1", null, Map.of()));
        assertNull(guard.check("s1", "", Map.of()));
    }

    // ── Config-explicit variant (security.doom_loop) ─────────────────

    @Test
    void disabledConfigDisablesTheGuard() {
        DoomLoopGuard guard = new DoomLoopGuard();
        Map<String, Object> cfg = Map.of("enabled", false);
        for (int i = 0; i < 10; i++) {
            assertNull(guard.check("s1", "read_file", Map.of("path", "a.md"), cfg),
                    "disabled guard must never deny");
        }
    }

    @Test
    void customThresholdsAreHonored() {
        DoomLoopGuard guard = new DoomLoopGuard();
        Map<String, Object> cfg = Map.of("warn_after", 2, "stop_after", 3);
        assertNull(guard.check("s1", "read_file", Map.of("path", "a.md"), cfg));
        String warn = guard.check("s1", "read_file", Map.of("path", "a.md"), cfg);
        assertTrue(warn.contains("疑似死循环"), warn);
        String stop = guard.check("s1", "read_file", Map.of("path", "a.md"), cfg);
        assertTrue(stop.contains("死循环保护"), stop);
    }

    @Test
    void stopAfterClampedToExceedWarnAfter() {
        DoomLoopGuard guard = new DoomLoopGuard();
        // stop_after == warn_after would make the warn tier unreachable;
        // the guard clamps stop to warn + 1.
        Map<String, Object> cfg = Map.of("warn_after", 3, "stop_after", 3);
        assertNull(guard.check("s1", "read_file", Map.of("path", "a.md"), cfg));
        assertNull(guard.check("s1", "read_file", Map.of("path", "a.md"), cfg));
        assertTrue(guard.check("s1", "read_file", Map.of("path", "a.md"), cfg).contains("疑似死循环"));
        assertTrue(guard.check("s1", "read_file", Map.of("path", "a.md"), cfg).contains("死循环保护"));
    }

    @Test
    void invalidConfigValuesFallBackToDefaults() {
        DoomLoopGuard guard = new DoomLoopGuard();
        Map<String, Object> cfg = Map.of("warn_after", "abc", "stop_after", "x");
        assertNull(guard.check("s1", "read_file", Map.of("path", "a.md"), cfg));
        assertNull(guard.check("s1", "read_file", Map.of("path", "a.md"), cfg));
        assertTrue(guard.check("s1", "read_file", Map.of("path", "a.md"), cfg).contains("疑似死循环"));
    }
}
