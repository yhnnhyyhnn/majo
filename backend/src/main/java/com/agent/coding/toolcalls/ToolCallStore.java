package com.agent.coding.toolcalls;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of running tool calls, mirroring qwenpaw's
 * ToolCoordinator surface for the {@code /api/tool-calls} endpoints.
 *
 * <p>Majo executes tools inside the AgentScope harness rather than through an
 * external coordinator, so this store is populated by callers that want
 * lifecycle management (cancel / extend-deadline / offload / output) exposed
 * to the frontend.
 */
@Component
public class ToolCallStore {

    public static final class ToolCallEntry {
        public final String toolCallId;
        public final String toolName;
        public final String sessionId;
        public final String agentId;
        public final long startedAt;
        public volatile String status; // running | done | cancelled | offloaded
        public volatile Double deadline; // epoch millis, or null
        public volatile boolean forceCancelled;
        public volatile String endState; // success | error | cancelled | offloaded
        public volatile String output;

        public ToolCallEntry(String toolCallId, String toolName, String sessionId,
                             String agentId, double startedAt) {
            this.toolCallId = toolCallId;
            this.toolName = toolName;
            this.sessionId = sessionId;
            this.agentId = agentId;
            this.startedAt = (long) startedAt;
            this.status = "running";
            this.deadline = null;
            this.forceCancelled = false;
            this.endState = null;
            this.output = "";
        }
    }

    private final ConcurrentHashMap<String, ToolCallEntry> entries = new ConcurrentHashMap<>();

    public ToolCallEntry register(String toolCallId, String toolName, String sessionId,
                                  String agentId, double startedAt) {
        ToolCallEntry entry = new ToolCallEntry(toolCallId, toolName, sessionId, agentId, startedAt);
        entries.put(toolCallId, entry);
        return entry;
    }

    public ToolCallEntry get(String toolCallId) {
        return entries.get(toolCallId);
    }

    public List<ToolCallEntry> list(String sessionId) {
        List<ToolCallEntry> result = new ArrayList<>();
        for (ToolCallEntry e : entries.values()) {
            if (sessionId == null || sessionId.equals(e.sessionId)) {
                result.add(e);
            }
        }
        result.sort((a, b) -> Long.compare(b.startedAt, a.startedAt));
        return result;
    }

    public boolean cancel(String toolCallId, boolean force) {
        ToolCallEntry e = entries.get(toolCallId);
        if (e == null || !"running".equals(e.status)) {
            return false;
        }
        e.status = "cancelled";
        e.endState = "cancelled";
        e.forceCancelled = force;
        return true;
    }

    public boolean extendDeadline(String toolCallId, Double seconds, boolean noDeadline) {
        ToolCallEntry e = entries.get(toolCallId);
        if (e == null || !"running".equals(e.status)) {
            return false;
        }
        if (noDeadline) {
            e.deadline = null;
        } else if (seconds != null && seconds > 0) {
            e.deadline = (double) (System.currentTimeMillis() + (long) (seconds * 1000));
        }
        return true;
    }

    public boolean offload(String toolCallId) {
        ToolCallEntry e = entries.get(toolCallId);
        if (e == null || !"running".equals(e.status)) {
            return false;
        }
        e.status = "offloaded";
        e.endState = "offloaded";
        return true;
    }

    public void finish(String toolCallId, String endState, String output) {
        ToolCallEntry e = entries.get(toolCallId);
        if (e == null) {
            return;
        }
        e.status = "done";
        e.endState = endState;
        e.output = output == null ? "" : output;
    }

    public Map<String, Object> toInfo(ToolCallEntry e) {
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("tool_call_id", e.toolCallId);
        info.put("tool_name", e.toolName);
        info.put("session_id", e.sessionId);
        info.put("agent_id", e.agentId);
        info.put("status", e.status);
        info.put("started_at", e.startedAt);
        info.put("deadline", e.deadline);
        info.put("elapsed", (System.currentTimeMillis() - e.startedAt) / 1000.0);
        info.put("extra", Map.of());
        info.put("end_state", e.endState);
        info.put("force_cancelled", e.forceCancelled);
        info.put("max_internal_timeout_secs", null);
        return info;
    }
}
