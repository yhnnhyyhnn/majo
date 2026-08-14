package com.agent.coding.approval;

import com.agent.coding.agent.AgentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Tool-execution approval hook, wired into HarnessAgent builds.
 *
 * <p>Listens to PRE_ACTING events and, based on the agent's
 * approval_level (STRICT / SMART / AUTO / OFF, default AUTO), decides
 * whether a tool call needs human approval. Pending requests surface through
 * ApprovalStore to the frontend pending_approvals list; the tool call blocks
 * until the user approves or denies via /api/approval/*. Timeouts deny.
 */
@Component
public class ApprovalHook implements io.agentscope.core.hook.Hook {

    private static final Logger log = LoggerFactory.getLogger(ApprovalHook.class);
    private static final long APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L;

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "read_file", "list_directory", "search_code", "find_symbol",
            "grep_search", "glob_search", "git_status", "git_diff",
            "git_branch", "git_log", "get_current_time", "view_image",
            "view_video", "web_fetch", "web_search");

    private static final Set<String> GUARDED_TOOLS = Set.of(
            "execute_shell_command", "write_file", "edit_file", "append_file",
            "git_commit", "git_add", "send_file_to_user", "browser_use",
            "desktop_screenshot", "execute_command", "bash");

    private final ApprovalStore store;

    public ApprovalHook(ApprovalStore store) {
        this.store = store;
    }

    @Override
    public <T extends io.agentscope.core.hook.HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof io.agentscope.core.hook.PreActingEvent acting)) {
            return Mono.just(event);
        }
        io.agentscope.core.message.ToolUseBlock toolUse = acting.getToolUse();
        if (toolUse == null) {
            return Mono.just(event);
        }
        String toolName = toolUse.getName();
        String agentId = agentIdOf(event);
        if (!needsApproval(agentId, toolName)) {
            return Mono.just(event);
        }

        String sessionId = sessionIdOf(event);
        ApprovalStore.ApprovalRequest req = store.register(
                sessionId, sessionId, agentId, toolName, toolName, "HIGH",
                APPROVAL_TIMEOUT_MS / 1000);
        log.info("[approval] waiting for {} on tool '{}' (agent={})",
                req.requestId, toolName, agentId);

        String decision = req.await(APPROVAL_TIMEOUT_MS);
        if ("approved".equals(decision)) {
            log.info("[approval] approved {} for tool '{}'", req.requestId, toolName);
            return Mono.just(event);
        }
        log.info("[approval] denied {} for tool '{}'", req.requestId, toolName);
        io.agentscope.core.message.ToolUseBlock rejected = io.agentscope.core.message.ToolUseBlock.builder()
                .id(toolUse.getId())
                .name(toolUse.getName())
                .input(toolUse.getInput())
                .content("User denied this tool call.")
                .state(io.agentscope.core.message.ToolCallState.FINISHED)
                .build();
        acting.setToolUse(rejected);
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 10;
    }

    private boolean needsApproval(String agentId, String toolName) {
        String level = AgentStore.getApprovalLevel(agentId);
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

    private static String agentIdOf(io.agentscope.core.hook.HookEvent event) {
        try {
            io.agentscope.core.agent.Agent agent = event.getAgent();
            if (agent != null && agent.getAgentId() != null && !agent.getAgentId().isBlank()) {
                return agent.getAgentId();
            }
        } catch (Exception ignored) {
        }
        return "default";
    }

    private static String sessionIdOf(io.agentscope.core.hook.HookEvent event) {
        try {
            io.agentscope.core.agent.Agent agent = event.getAgent();
            if (agent != null && agent.getAgentState() != null) {
                String sid = agent.getAgentState().getSessionId();
                if (sid != null && !sid.isBlank()) {
                    return sid;
                }
            }
        } catch (Exception ignored) {
        }
        return "session-" + System.nanoTime();
    }
}