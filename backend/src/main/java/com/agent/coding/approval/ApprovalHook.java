package com.agent.coding.approval;

import com.agent.coding.agent.AgentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Tool-execution approval flow. Since agentscope 2.1.0 this is invoked by
 * {@code SessionScopeMiddleware.onActing} with the authoritative session id
 * (ADR-0022) — no longer an event hook.
 *
 * <p>Based on the agent's approval_level (STRICT / SMART / MODEL / AUTO /
 * OFF, default AUTO), decides whether a tool call needs approval: MODEL
 * consults the decision model (ADR-0021), the other levels register a human
 * card through ApprovalStore (surfaced via pending_approvals); the caller
 * blocks until the user approves or denies via /api/approval/*. Timeouts
 * deny.
 */
@Component
public class ApprovalHook {

    private static final Logger log = LoggerFactory.getLogger(ApprovalHook.class);
    private static final long APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L;

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "read_file", "list_directory", "search_code", "find_symbol",
            "grep_search", "glob_search", "git_status", "git_diff",
            "git_branch", "git_log", "get_current_time", "view_image",
            "view_video", "view_audio", "web_fetch", "web_search");

    private static final Set<String> GUARDED_TOOLS = Set.of(
            "execute_shell_command", "write_file", "edit_file", "append_file",
            "git_commit", "git_add", "send_file_to_user", "browser_use",
            "desktop_screenshot", "execute_command", "bash");

    private final ApprovalStore store;
    private final DecisionModelGate decisionModelGate;

    public ApprovalHook(ApprovalStore store, DecisionModelGate decisionModelGate) {
        this.store = store;
        this.decisionModelGate = decisionModelGate;
    }

    /**
     * Level-based gate: returns the block to execute — the original when
     * approved, or a FINISHED rejection when denied. No-op for tools the
     * level exempts.
     */
    public io.agentscope.core.message.ToolUseBlock gateAndAwait(String sessionId, String agentId,
                                                                io.agentscope.core.message.ToolUseBlock toolUse) {
        if (toolUse == null || !needsApproval(agentId, toolUse.getName())) {
            return toolUse;
        }
        // MODEL level: the decision model judges instead of a human card
        // (ADR-0021). Fail-closed throughout — no pending card, no Inbox.
        if ("MODEL".equalsIgnoreCase(levelOf(agentId))) {
            DecisionModelGate.Verdict verdict = decisionModelGate.judge(
                    agentId, toolUse.getName(), toolUseInput(toolUse));
            if (verdict.approved()) {
                log.info("[approval] decision model approved '{}' (agent={})", toolUse.getName(), agentId);
                return toolUse;
            }
            log.info("[approval] decision model denied '{}' (agent={}): {}",
                    toolUse.getName(), agentId, verdict.reason());
            return rejected(toolUse, verdict.reason());
        }

        // Human card: register, block until decided (timeout denies).
        ApprovalStore.ApprovalRequest req = store.register(
                sessionId, sessionId, agentId, toolUse.getName(), toolUse.getName(), "HIGH",
                APPROVAL_TIMEOUT_MS / 1000);
        log.info("[approval] waiting for {} on tool '{}' (agent={})",
                req.requestId, toolUse.getName(), agentId);

        String decision = req.await(APPROVAL_TIMEOUT_MS);
        if ("approved".equals(decision)) {
            log.info("[approval] approved {} for tool '{}'", req.requestId, toolUse.getName());
            return toolUse;
        }
        log.info("[approval] denied {} for tool '{}'", req.requestId, toolUse.getName());
        return rejected(toolUse, "User denied this tool call.");
    }

    /**
     * Human-only flow for MCP tools whose access policy resolves to "ask"
     * (ADR-0007): returns "approved" when approved, otherwise the rejection
     * text. The decision model never substitutes here — an explicit "ask"
     * policy means a human.
     */
    public String humanCardFlow(String sessionId, String agentId,
                                io.agentscope.core.message.ToolUseBlock toolUse) {
        ApprovalStore.ApprovalRequest req = store.register(
                sessionId, sessionId, agentId, toolUse.getName(), toolUse.getName(), "HIGH",
                APPROVAL_TIMEOUT_MS / 1000);
        log.info("[approval] waiting for {} on tool '{}' (agent={}) [mcp ask]",
                req.requestId, toolUse.getName(), agentId);
        String decision = req.await(APPROVAL_TIMEOUT_MS);
        return "approved".equals(decision) ? "approved" : "User denied this tool call.";
    }

    private static String levelOf(String agentId) {
        String level = AgentStore.getApprovalLevel(agentId);
        return level == null ? "AUTO" : level.trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolUseInput(io.agentscope.core.message.ToolUseBlock toolUse) {
        return toolUse.getInput() instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
    }

    private static io.agentscope.core.message.ToolUseBlock rejected(
            io.agentscope.core.message.ToolUseBlock toolUse, String reason) {
        return io.agentscope.core.message.ToolUseBlock.builder()
                .id(toolUse.getId())
                .name(toolUse.getName())
                .input(toolUse.getInput())
                .content(reason)
                .state(io.agentscope.core.message.ToolCallState.FINISHED)
                .build();
    }

    private boolean needsApproval(String agentId, String toolName) {
        String level = AgentStore.getApprovalLevel(agentId);
        if (level == null) {
            level = "AUTO";
        }
        switch (level == null ? "AUTO" : level.trim().toUpperCase()) {
            case "OFF":
                return false;
            case "STRICT":
                return true;
            case "SMART":
                return !READ_ONLY_TOOLS.contains(toolName);
            case "AUTO":
            default:
                return GUARDED_TOOLS.contains(toolName);
        }
    }
}
