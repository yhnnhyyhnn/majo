package com.agent.coding.controller;

import com.agent.coding.approval.ApprovalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool-guard approval actions, ported from qwenpaw app/routers/approval.py.
 * Resolves a pending tool-execution request registered in
 * {@link ApprovalStore} (which backs the {@code pending_approvals} list in
 * {@code /api/console/push-messages}).
 */
@RestController
@RequestMapping("/api/approval")
@CrossOrigin(origins = "*")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalStore store;

    public ApprovalController(ApprovalStore store) {
        this.store = store;
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String session_id) {
        List<ApprovalStore.ApprovalRequest> pendingList = store.listPending();
        if (session_id != null && !session_id.isBlank()) {
            pendingList = pendingList.stream()
                    .filter(r -> session_id.equals(r.rootSessionId) || session_id.equals(r.sessionId))
                    .toList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApprovalStore.ApprovalRequest p : pendingList) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("request_id", p.requestId);
            item.put("session_id", p.sessionId);
            item.put("root_session_id", p.rootSessionId);
            item.put("owner_agent_id", p.agentId);
            item.put("agent_id", p.agentId);
            item.put("tool_name", p.toolName);
            item.put("tool_display_name", p.toolDisplayName);
            item.put("severity", p.severity);
            item.put("findings_count", 0);
            item.put("created_at", java.time.Instant.ofEpochMilli(p.createdAt).toString());
            item.put("timeout_seconds", p.timeoutSeconds);
            item.put("result_summary", null);
            result.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pending_approvals", result);
        response.put("count", result.size());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approve(@RequestBody Map<String, Object> body) {
        String requestId = str(body.get("request_id"));
        String sessionId = str(body.get("session_id"));
        if (requestId.isBlank() || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "request_id and session_id are required"));
        }
        ApprovalStore.ApprovalRequest req = store.get(requestId);
        if (req == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "Approval request not found: " + shortId(requestId)));
        }
        if (!req.rootSessionId.equals(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Root session mismatch: cannot approve other session trees"));
        }
        String scope = body.get("scope") == null ? null : str(body.get("scope")).trim().toLowerCase();
        String decision = "exact".equals(scope) || "similar".equals(scope) ? scope : null;
        store.resolve(requestId, "approved", decision == null ? "exact" : decision);
        log.info("[approval] approved request={} session={} tool={}", shortId(requestId), sessionId, req.toolName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Tool '" + req.toolName + "' approved, executing...");
        result.put("tool_name", req.toolName);
        result.put("request_id", requestId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/deny")
    public ResponseEntity<Map<String, Object>> deny(@RequestBody Map<String, Object> body) {
        String requestId = str(body.get("request_id"));
        String sessionId = str(body.get("session_id"));
        if (requestId.isBlank() || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "request_id and session_id are required"));
        }
        ApprovalStore.ApprovalRequest req = store.get(requestId);
        if (req == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "Approval request not found: " + shortId(requestId)));
        }
        if (!req.rootSessionId.equals(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Root session mismatch: cannot deny other session trees"));
        }
        String reason = body.get("reason") == null ? "User denied" : str(body.get("reason"));
        store.resolve(requestId, "denied", reason);
        log.info("[approval] denied request={} session={} tool={} reason={}", shortId(requestId), sessionId, req.toolName, reason);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Tool '" + req.toolName + "' denied: " + reason);
        result.put("tool_name", req.toolName);
        result.put("request_id", requestId);
        return ResponseEntity.ok(result);
    }

    private static String shortId(String id) {
        return id.length() <= 16 ? id : id.substring(0, 16);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
