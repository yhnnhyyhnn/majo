package com.agent.coding.approval;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-memory registry of pending tool-execution approvals
 * app/approvals (ApprovalService). Agents register a pending request before
 * executing a guarded tool; the frontend lists them via
 * {@code /api/console/push-messages} and resolves them via
 * {@code /api/approval/approve|deny}.
 *
 * <p>Each pending request carries a {@link CompletableFuture} that the tool
 * executor blocks on; {@link #resolve} completes it so the tool call can
 * proceed or abort. Thread-safe; entries are keyed by {@code request_id} and
 * carry the root {@code session_id} so cross-session resolution is validated.
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
        public final CompletableFuture<String> future;
        public volatile boolean resolved;
        public volatile String decision; // "approved" | "denied"
        public volatile String reason;

        /** Owning store; drops this request from pending when await() expires. */
        ApprovalStore owner;

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
            this.future = new CompletableFuture<>();
            this.resolved = false;
            this.decision = null;
            this.reason = null;
        }

        /** Block until resolved (or timeout), returning the decision. */
        public String await(long timeoutMillis) {
            // Never wait past the request's own deadline: the card expires
            // with the request, not with each individual waiter.
            long deadline = deadlineMillis();
            long waitMillis = timeoutMillis;
            if (deadline != Long.MAX_VALUE) {
                waitMillis = Math.min(waitMillis, Math.max(0L, deadline - System.currentTimeMillis()));
            }
            try {
                return future.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // The waiter's timeout raced a decision: settle expiry through
                // the same arbiter so a decision that already won is returned
                // instead of being silently downgraded to a denial.
                if (settle("denied", "timeout")) {
                    if (owner != null) {
                        owner.pending.remove(requestId);
                    }
                    return "denied";
                }
                return future.getNow("denied");
            } catch (Exception e) {
                return "denied";
            }
        }

        /** Deadline in epoch millis; {@code Long.MAX_VALUE} when untimed. */
        long deadlineMillis() {
            return timeoutSeconds > 0 ? createdAt + timeoutSeconds * 1000 : Long.MAX_VALUE;
        }

        boolean isExpired(long now) {
            return timeoutSeconds > 0 && now >= deadlineMillis();
        }

        /**
         * First-writer-wins settlement: the future's completion is the only
         * arbiter, so a decision and a timeout racing each other can never
         * both take effect, and the stored fields are never overwritten by
         * a late second resolver. Returns false when this call lost the race.
         */
        boolean settle(String decision, String reason) {
            String value = "approved".equals(decision) ? "approved" : "denied";
            if (!future.complete(value)) {
                return false;
            }
            this.decision = value;
            this.reason = reason;
            this.resolved = true;
            return true;
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
        req.owner = this;
        pending.put(requestId, req);
        history.add(req);
        return req;
    }

    public ApprovalRequest get(String requestId) {
        return pending.get(requestId);
    }

    /**
     * Pending or already-settled request by id — settled requests leave the
     * pending map but stay in history, letting callers tell "never existed"
     * apart from "resolved or expired".
     */
    public ApprovalRequest findAny(String requestId) {
        ApprovalRequest req = pending.get(requestId);
        if (req != null) {
            return req;
        }
        for (ApprovalRequest r : history) {
            if (r.requestId.equals(requestId)) {
                return r;
            }
        }
        return null;
    }

    public List<ApprovalRequest> listPending() {
        settleExpired(System.currentTimeMillis());
        return pending.values().stream()
                .filter(r -> !r.resolved)
                .sorted((a, b) -> Long.compare(b.createdAt, a.createdAt))
                .toList();
    }

    public List<ApprovalRequest> listHistory() {
        return List.copyOf(history);
    }

    /**
     * Resolve a pending request; returns the settled request, or null when
     * the request is unknown, already resolved, or past its deadline — an
     * approval arriving after expiry never overrides the timeout denial
     * (the agent has already proceeded), so late resolvers must report
     * conflict rather than success.
     */
    public ApprovalRequest resolve(String requestId, String decision, String reason) {
        ApprovalRequest req = pending.get(requestId);
        if (req == null) {
            return null;
        }
        if (req.isExpired(System.currentTimeMillis())) {
            req.settle("denied", "timeout");
            pending.remove(requestId);
            return null;
        }
        if (!req.settle(decision, reason)) {
            return null;
        }
        pending.remove(requestId);
        return req;
    }

    /**
     * Settle requests past their deadline as timeout denials and drop them,
     * so expired cards stop surfacing in pending lists and clicking them
     * reports conflict instead of a phantom success.
     */
    void settleExpired(long now) {
        pending.values().removeIf(r -> r.resolved || (r.isExpired(now) && r.settle("denied", "timeout")));
    }

    /** Drop stale pending entries (e.g. on session end). */
    public void purgeExpired() {
        settleExpired(System.currentTimeMillis());
    }
}
