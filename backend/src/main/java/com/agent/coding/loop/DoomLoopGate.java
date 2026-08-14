package com.agent.coding.loop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-stage doom loop gate (session-safe), ported from qwenpaw
 * loop/gates/doom_loop.py. Sliding-window repetition detection that
 * escalates: modify_prompt warns and injects a continuation, stop terminates.
 */
public class DoomLoopGate extends LoopGate {

    /** One escalation stage: trigger after N consecutive repetitions. */
    public record Stage(int after, String action, String prompt) {
        public static Stage warning(int after, String prompt) {
            return new Stage(after, "modify_prompt", prompt);
        }

        public static Stage stop(int after, String prompt) {
            return new Stage(after, "stop", prompt);
        }
    }

    private static final class ToolCallRecord {
        final String toolName;
        final String argsHash;

        ToolCallRecord(String toolName, String argsHash) {
            this.toolName = toolName;
            this.argsHash = argsHash;
        }
    }

    private static final class DoomState {
        final ArrayDeque<ToolCallRecord> history;
        int consecutiveHits;
        String prompt = "";
        int lastRecordedIter = -1;

        DoomState(int windowSize) {
            this.history = new ArrayDeque<>(Math.max(windowSize * 2, 4));
        }
    }

    private final int windowSize;
    private final double threshold;
    private final List<Stage> stages;

    public DoomLoopGate(int windowSize, double threshold, List<Stage> stages) {
        this.windowSize = Math.max(2, windowSize);
        this.threshold = threshold;
        this.stages = stages == null ? List.of() : new ArrayList<>(stages);
        this.stages.sort((a, b) -> Integer.compare(a.after(), b.after()));
    }

    public static DoomLoopGate defaultConfig() {
        return new DoomLoopGate(3, 1.0, List.of(
                Stage.warning(3, "[WARNING] Repetitive pattern detected. You are repeating "
                        + "similar actions without progress. Try a completely different approach."),
                Stage.stop(4, "Doom loop: agent stuck after 4 consecutive repetitions")
        ));
    }

    @Override
    public String name() {
        return "doom-loop";
    }

    @Override
    public int priority() {
        return 5;
    }

    /** Record a completed tool call (also auto-collected via ctx on check). */
    public void record(String toolName, String argsHash) {
        DoomState state = ensureState();
        state.history.add(new ToolCallRecord(toolName, argsHash));
    }

    @Override
    public StopHandlerResult check(LoopContext ctx) {
        DoomState state = ensureState();
        if (ctx.lastToolCall != null && ctx.iteration > state.lastRecordedIter) {
            state.lastRecordedIter = ctx.iteration;
            state.history.add(new ToolCallRecord(
                    ctx.lastToolCall.toolName(), ctx.lastToolCall.argsHash()));
        }

        boolean looping = detectRepetition(state);
        if (!looping) {
            state.consecutiveHits = 0;
            state.prompt = "";
            return StopHandlerResult.bypass();
        }

        if (state.consecutiveHits == 0) {
            state.consecutiveHits = windowSize;
        } else {
            state.consecutiveHits += 1;
        }

        Stage activeStage = null;
        for (int i = stages.size() - 1; i >= 0; i--) {
            if (state.consecutiveHits >= stages.get(i).after()) {
                activeStage = stages.get(i);
                break;
            }
        }
        if (activeStage == null) {
            return StopHandlerResult.bypass();
        }

        if ("stop".equals(activeStage.action())) {
            return StopHandlerResult.terminate(activeStage.prompt());
        }
        state.prompt = activeStage.prompt();
        return StopHandlerResult.continueWith(activeStage.prompt(), "doom_loop repetition warning");
    }

    @Override
    public String buildContinuation() {
        DoomState state = (DoomState) state();
        return state == null ? "" : state.prompt;
    }

    @Override
    public void resetTurn() {
        DoomState state = (DoomState) state();
        if (state != null) {
            state.history.clear();
            state.consecutiveHits = 0;
            state.prompt = "";
            state.lastRecordedIter = -1;
        }
    }

    private DoomState ensureState() {
        DoomState state = (DoomState) state();
        if (state == null) {
            state = new DoomState(windowSize);
            activate(state);
        }
        return state;
    }

    private boolean detectRepetition(DoomState state) {
        if (state.history.size() < windowSize) {
            return false;
        }
        List<ToolCallRecord> window = new ArrayList<>(state.history);
        int start = Math.max(0, window.size() - windowSize);
        window = window.subList(start, window.size());
        return similarity(window) >= threshold;
    }

    private static double similarity(List<ToolCallRecord> window) {
        if (window == null || window.size() <= 1) {
            return 0.0;
        }
        Set<String> sigs = new HashSet<>();
        for (ToolCallRecord r : window) {
            sigs.add(r.toolName + ":" + r.argsHash);
        }
        int unique = sigs.size();
        int total = window.size();
        return 1.0 - (double) (unique - 1) / (total - 1);
    }

    /** Hash tool call args (first 2048 bytes), matching qwenpaw _hash_args. */
    public static String hashArgs(String rawInput) {
        if (rawInput == null) {
            rawInput = "";
        }
        byte[] data = rawInput.getBytes(StandardCharsets.UTF_8);
        if (data.length > 2048) {
            byte[] truncated = new byte[2048];
            System.arraycopy(data, 0, truncated, 0, 2048);
            data = truncated;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(data.hashCode());
        }
    }
}
