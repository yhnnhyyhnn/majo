package com.agent.coding.loop;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-safe base for all stateful gates, ported from qwenpaw
 * loop/gates/loop_gate.py LoopGate. Manages per-session state keyed by
 * session ID; subclasses store arbitrary state via {@link #activate(Object)}
 * and retrieve it via {@link #state()}.
 */
public abstract class LoopGate extends StopGate {

    private final ConcurrentHashMap<String, Object> sessions = new ConcurrentHashMap<>();

    /** Session id resolver; the loop runtime sets it before evaluation. */
    private static final ThreadLocal<String> CURRENT_SESSION = new ThreadLocal<>();

    public static void setCurrentSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            CURRENT_SESSION.remove();
        } else {
            CURRENT_SESSION.set(sessionId);
        }
    }

    public static void clearCurrentSession() {
        CURRENT_SESSION.remove();
    }

    protected String sessionId() {
        String sid = CURRENT_SESSION.get();
        return sid == null ? "default" : sid;
    }

    /** Return per-session state or null. */
    protected Object state() {
        return sessions.get(sessionId());
    }

    /** Activate gate for current session. */
    protected void activate(Object state) {
        sessions.put(sessionId(), state);
    }

    /** Deactivate gate for current session. */
    protected void deactivate() {
        sessions.remove(sessionId());
    }

    @Override
    public void resetSession() {
        deactivate();
    }

    /** Remove state for a specific session (used by session teardown). */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }
}
