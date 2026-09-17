package com.agent.coding.acp;

import com.agent.coding.approval.ApprovalStore;
import com.agent.coding.skill.SkillService;
import com.agent.coding.tool.ToolSessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges an ACP runner's {@code session/request_permission} to majo's
 * approval flow (phase 2 of the ACP roadmap, counterpart of QwenPaw
 * #7783's permission negotiation).
 *
 * <p>When a delegated runner asks for permission:
 * <ul>
 *   <li><b>Interactive context</b> (the delegated run executes inside a
 *       live majo chat session): a pending approval card is registered on
 *       that session — the same surface users already know from majo's own
 *       tool approvals — and the decision is mapped back to the ACP outcome
 *       (first allow-kind option on approve, cancelled on deny/timeout);</li>
 *   <li><b>Non-interactive context</b> (heartbeat/cron/delegated-in-delegated):
 *       immediately denied, same as before this bridge existed.</li>
 * </ul>
 */
@Component
public class AcpPermissionBridge {

    private static final Logger log = LoggerFactory.getLogger(AcpPermissionBridge.class);
    private static final long APPROVAL_TIMEOUT_SECONDS = 60;

    private final ApprovalStore approvalStore;

    public AcpPermissionBridge(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

    /** One permission option advertised by the runner. */
    public record PermissionOption(String optionId, String name, String kind) {
        boolean isAllowKind() {
            String k = kind == null ? "" : kind.toLowerCase();
            return k.equals("allow_once") || k.equals("allow_always");
        }
    }

    /** The reply payload for the runner, plus a readable card summary. */
    public record Decision(Map<String, Object> replyPayload, String cardSummary) {}

    /**
     * Resolve a runner permission request into the JSON-RPC reply payload.
     * Returns {@code {"outcome": {"optionId", "outcome": "selected"}}} on
     * approval, {@code {"outcome": {"outcome": "cancelled"}}} on
     * deny/timeout/non-interactive.
     */
    public Map<String, Object> resolve(JsonNode params) {
        return resolve(params, APPROVAL_TIMEOUT_SECONDS);
    }

    /** Overload with a bounded wait (package/test tunable). */
    public Map<String, Object> resolve(JsonNode params, long timeoutSeconds) {
        List<PermissionOption> options = parseOptions(params);
        String toolTitle = toolTitleOf(params);

        String sessionId = ToolSessionContext.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            // Non-interactive run (heartbeat/cron/nested) — deny immediately.
            return cancelled();
        }

        ApprovalStore.ApprovalRequest req = approvalStore.register(
                sessionId, sessionId, ToolSessionContext.agentId(),
                "ACP:" + toolTitle, toolTitle, "HIGH", timeoutSeconds);
        log.info("[acp-perm] waiting for {} — runner tool '{}' ({} option(s))",
                req.requestId, toolTitle, options.size());

        String decision = req.await(timeoutSeconds * 1000);
        if ("approved".equals(decision)) {
            String optionId = firstAllowOptionId(options);
            Map<String, Object> outcome = new LinkedHashMap<>();
            if (optionId != null) {
                outcome.put("optionId", optionId);
            }
            outcome.put("outcome", "selected");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("outcome", outcome);
            log.info("[acp-perm] approved {} (optionId={})", req.requestId, optionId);
            return payload;
        }
        log.info("[acp-perm] denied/timeout {} — replying cancelled", req.requestId);
        return cancelled();
    }

    /** Parse the runner's advertised options (spec: params.options[]). */
    List<PermissionOption> parseOptions(JsonNode params) {
        List<PermissionOption> out = new ArrayList<>();
        if (params != null && params.path("options").isArray()) {
            for (JsonNode o : params.path("options")) {
                out.add(new PermissionOption(
                        o.path("optionId").asText(""),
                        o.path("name").asText(""),
                        o.path("kind").asText("")));
            }
        }
        return out;
    }

    /** Human-readable tool title from params.toolCall (update payload). */
    static String toolTitleOf(JsonNode params) {
        JsonNode call = params == null ? null : params.path("toolCall");
        String title = call == null ? "" : call.path("title").asText("");
        if (title.isBlank()) {
            title = params == null ? "" : params.path("toolCall").path("rawInput").toString();
        }
        if (title.isBlank()) {
            return "delegated action";
        }
        return title.length() > 80 ? title.substring(0, 80) + "…" : title;
    }

    private static String firstAllowOptionId(List<PermissionOption> options) {
        for (PermissionOption o : options) {
            if (o.isAllowKind() && !o.optionId().isBlank()) {
                return o.optionId();
            }
        }
        return null;
    }

    private static Map<String, Object> cancelled() {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("outcome", "cancelled");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", outcome);
        return payload;
    }
}
