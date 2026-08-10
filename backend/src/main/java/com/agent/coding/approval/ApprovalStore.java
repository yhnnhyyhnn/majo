package com.agent.coding.approval;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory registry of pending tool-execution approvals, ported from qwenpaw
 * app/approvals (ApprovalService). Agents register a pending request before
 * executing a guarded tool; the frontend lists them via
 * {@code /api/console/push-messages} and resolves them via
 * {@code /api/approval/approve|deny}.
 *
 * <p>Thread-safe; entries are keyed by {@code request_id} and carry the root
 * {@code session_id} so cross-session resolution can be validated.
 */
@Component
public class ApprovalStore {

    /** Mutable pending request; resolution flips {@code resolved} + {@code decision}. */
    public static final class ApprovalRequest {
        public final String requestId;
        public final String sessionId;
        public final String rootSessionId;
        public final String agentId;
        public final String toolName;
        public final String toolDisplayName;
        public final String severity;
        public final long createdAt;
        public final long timeoutSeconds;
        public volatile boolean resolved;
        public volatile String decision; // "approved" | "denied"
        public volatile String reason;

        public ApprovalRequest(String requestId, String sessionId, String rootSessionId,
                               String agentId, String toolName, String toolDisplayName,
                               String severity, long timeoutSeconds) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.rootSessionId = rootSessionId;
            this.agentId = agentId;
            this.toolName = toolName;
            this.toolDisplayName = toolDisplayName;
            this.severity = severity;
            this.createdAt = System.currentTimeMillis();
            this.timeoutSeconds = timeoutSeconds;
            this.resolved = false;
            this.decision = null;
            this.reason = null;
        }
    }

    private final ConcurrentHashMap<String, ApprovalRequest> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ApprovalRequest> history = new CopyOnWriteArrayList<>();

    public ApprovalRequest register(String sessionId, String rootSessionId, String agentId,
                                    String toolName, String toolDisplayName, String severity,
                                    long timeoutSeconds) {
        String requestId = java.util.UUID.randomUUID().toString();
        ApprovalRequest req = new ApprovalRequest(requestId, sessionId, rootSessionId,
                agentId, toolName, toolDisplayName, severity, timeoutSeconds);
        pending.put(requestId, req);
        history.add(req);
        return req;
    }

    public ApprovalRequest get(String requestId) {
        return pending.get(requestId);
    }

    public List<ApprovalRequest> listPending() {
        return pending.values().stream()
                .filter(r -> !r.resolved)
                .sorted((a, b) -> Long.compare(b.createdAt, a.createdAt))
                .toList();
    }

    public List<ApprovalRequest> listHistory() {
        return List.copyOf(history);
    }

    /** Resolve a pending request; returns null if unknown. */
    public ApprovalRequest resolve(String requestId, String decision, String reason) {
        ApprovalRequest req = pending.get(requestId);
        if (req == null || req.resolved) {
            return null;
        }
        req.resolved = true;
        req.decision = decision;
        req.reason = reason;
        pending.remove(requestId);
        return req;
    }

    /** Drop stale pending entries (e.g. on session end). */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(r -> {
            if (r.resolved) {
                return true;
            }
            return r.timeoutSeconds > 0 && now - r.createdAt > r.timeoutSeconds * 1000;
        });
    }
}
