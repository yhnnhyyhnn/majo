package com.agent.coding.loop;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active loop-mode sessions, mirroring qwenpaw's GoalSession registry.
 * Drives /loops/status (running vs idle) and provides the gate wiring for
 * goal/mission mode executions.
 */
@Component
public class LoopSessionManager {

    /** One active loop session bound to a conversation. */
    public static class LoopSession {
        public final String sessionId;
        public final String modeId;
        public final String modeName;
        public final String goal;
        public final StopHandler handler;
        public volatile String state; // "running" | "awaiting_user" | "idle"
        public volatile int iteration;
        public volatile String lastResult = "";

        public LoopSession(String sessionId, String modeId, String modeName,
                           String goal, StopHandler handler) {
            this.sessionId = sessionId;
            this.modeId = modeId;
            this.modeName = modeName;
            this.goal = goal;
            this.handler = handler;
            this.state = "running";
        }
    }

    private final ConcurrentHashMap<String, LoopSession> sessions = new ConcurrentHashMap<>();

    public LoopSession start(String sessionId, String modeId, String modeName,
                             String goal, StopHandler handler) {
        LoopSession session = new LoopSession(sessionId, modeId, modeName, goal, handler);
        LoopSession prev = sessions.put(sessionId, session);
        if (prev != null) {
            prev.handler.resetSession();
        }
        return session;
    }

    public LoopSession get(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    public void end(String sessionId) {
        LoopSession session = sessionId == null ? null : sessions.remove(sessionId);
        if (session != null) {
            session.state = "idle";
            session.handler.resetSession();
        }
    }

    public void setAwaitingUser(String sessionId) {
        LoopSession session = get(sessionId);
        if (session != null) {
            session.state = "awaiting_user";
        }
    }

    public void setRunning(String sessionId) {
        LoopSession session = get(sessionId);
        if (session != null) {
            session.state = "running";
        }
    }

    public boolean isActive(String sessionId) {
        LoopSession session = get(sessionId);
        return session != null && !"idle".equals(session.state);
    }

    /** Status payload for /loops/status. */
    public Map<String, Object> status(String sessionId) {
        LoopSession session = get(sessionId);
        if (session == null || "idle".equals(session.state)) {
            Map<String, Object> idle = new LinkedHashMap<>();
            idle.put("state", "idle");
            idle.put("mode", null);
            return idle;
        }
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("id", session.modeId);
        mode.put("name", session.modeName);
        mode.put("slash_command", session.modeId);
        mode.put("description", "Active " + session.modeName + " loop.");
        mode.put("source", "builtin");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", session.state);
        result.put("mode", mode);
        return result;
    }
}
