package com.agent.coding.cron;

import com.agent.coding.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Heartbeat scheduler unit tests (ADR-0013): interval/cron parsing with
 * QwenPaw semantics, and the run-status contract (a status is always
 * recorded — skipped/success/timeout/error, never stuck at "never").
 */
class HeartbeatSchedulerTest {

    @TempDir
    Path workspace;

    private static SettingsService settings(boolean enabled, String every,
                                            String target, int timeoutSeconds) {
        SettingsService s = org.mockito.Mockito.mock(SettingsService.class);
        org.mockito.Mockito.when(s.isHeartbeatEnabled()).thenReturn(enabled);
        org.mockito.Mockito.when(s.getHeartbeatEvery()).thenReturn(every);
        org.mockito.Mockito.when(s.getHeartbeatTarget()).thenReturn(target);
        org.mockito.Mockito.when(s.getHeartbeatTimeoutSeconds()).thenReturn(timeoutSeconds);
        return s;
    }

    // ── Schedule parsing ─────────────────────────────────────────────

    @Test
    void parsesIntervalStrings() {
        assertEquals(Duration.ofSeconds(90), HeartbeatScheduler.parseInterval("90s"));
        assertEquals(Duration.ofMinutes(30), HeartbeatScheduler.parseInterval("30m"));
        assertEquals(Duration.ofHours(6), HeartbeatScheduler.parseInterval("6h"));
        assertEquals(Duration.ofHours(2).plusMinutes(30), HeartbeatScheduler.parseInterval("2h30m"));
    }

    @Test
    void invalidIntervalsFallBackToDefault() {
        assertEquals(Duration.ofHours(6), HeartbeatScheduler.parseInterval("every 6 hours"));
        assertEquals(Duration.ofHours(6), HeartbeatScheduler.parseInterval(""));
        assertEquals(Duration.ofHours(6), HeartbeatScheduler.parseInterval(null));
        assertEquals(Duration.ofHours(6), HeartbeatScheduler.parseInterval("0s"));
        assertEquals(Duration.ofHours(6), HeartbeatScheduler.parseInterval("3x"));
    }

    @Test
    void cronDetectionRequiresFiveValidFields() {
        assertTrue(HeartbeatScheduler.isCronExpression("0 */6 * * *"));
        assertTrue(HeartbeatScheduler.isCronExpression("30 9 * * mon-fri"));
        assertFalse(HeartbeatScheduler.isCronExpression("6h"));
        assertFalse(HeartbeatScheduler.isCronExpression("0 */6 * *"));
        assertFalse(HeartbeatScheduler.isCronExpression("not a cron at all here ok"));
        assertFalse(HeartbeatScheduler.isCronExpression(null));
    }

    // ── Active-hours window (ADR-0013 follow-up) ────────────────────

    @Test
    void parsesHHmmWindows() {
        assertEquals(Integer.valueOf(0), HeartbeatScheduler.parseHHmm("00:00"));
        assertEquals(Integer.valueOf(8 * 60), HeartbeatScheduler.parseHHmm("08:00"));
        assertEquals(Integer.valueOf(22 * 60 + 30), HeartbeatScheduler.parseHHmm("22:30"));
        assertEquals(Integer.valueOf(9 * 60 + 5), HeartbeatScheduler.parseHHmm("9:05"));
        assertNull(HeartbeatScheduler.parseHHmm(null));
        assertNull(HeartbeatScheduler.parseHHmm(""));
        assertNull(HeartbeatScheduler.parseHHmm("24:00"));
        assertNull(HeartbeatScheduler.parseHHmm("08:60"));
        assertNull(HeartbeatScheduler.parseHHmm("8am"));
    }

    // ── Run status contract ──────────────────────────────────────────

    @Test
    void runSafelyAlwaysRecordsAStatus() {
        // AgentStore's default workspace may or may not contain HEARTBEAT.md;
        // the contract under test is that a status is always recorded.
        HeartbeatScheduler scheduler = new HeartbeatScheduler(
                settings(true, "6h", "main", 120), null, null, null, null, null);
        assertEquals("never", scheduler.lastRunSummary().get("status"));
        scheduler.runSafely();
        String status = String.valueOf(scheduler.lastRunSummary().get("status"));
        assertTrue(List.of("skipped", "success", "timeout", "error").contains(status), status);
    }

    @Test
    void runNowRespectsEnabledFlag() {
        HeartbeatScheduler disabled = new HeartbeatScheduler(
                settings(false, "6h", "main", 120), null, null, null, null, null);
        Map<String, Object> result = disabled.runNow();
        assertEquals(false, result.get("started"));
        assertEquals("never", ((Map<?, ?>) result.get("last_run")).get("status"));
    }

    @Test
    void runNowOnEnabledSchedulerStarts() {
        HeartbeatScheduler scheduler = new HeartbeatScheduler(
                settings(true, "6h", "main", 120), null, null, null, null, null);
        Map<String, Object> result = scheduler.runNow();
        assertEquals(true, result.get("started"));
    }
}
