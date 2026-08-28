package com.agent.coding.security;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.approval.ApprovalHook;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Map;

/**
 * Tool execution security hook, wired into every HarnessAgent build.
 *
 * <p>Intercepts PRE_ACTING events and enforces, in order:
 * <ol>
 *   <li>{@link ToolGuardService} — denied tools, built-in/custom rules,
 *       shell-evasion heuristics;</li>
 *   <li>{@link FileGuardService} — workspace containment + protected paths
 *       for file tools;</li>
 *   <li>{@link ApprovalHook} — human approval for guarded tools (delegated,
 *       preserving the existing approval flow).</li>
 * </ol>
 *
 * <p>Blocked calls are replaced with a FINISHED tool result carrying the
 * guard message, so the agent sees why the call was rejected (same pattern
 * as ApprovalHook's denial).
 */
@Component
public class ToolGuardHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolGuardHook.class);

    private final ToolGuardService toolGuardService;
    private final FileGuardService fileGuardService;
    private final ApprovalHook approvalHook;

    public ToolGuardHook(ToolGuardService toolGuardService,
                         FileGuardService fileGuardService,
                         ApprovalHook approvalHook) {
        this.toolGuardService = toolGuardService;
        this.fileGuardService = fileGuardService;
        this.approvalHook = approvalHook;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreActingEvent acting)) {
            return Mono.just(event);
        }
        ToolUseBlock toolUse = acting.getToolUse();
        if (toolUse == null) {
            return Mono.just(event);
        }
        String toolName = toolUse.getName();
        @SuppressWarnings("unchecked")
        Map<String, Object> input = toolUse.getInput() instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        // 1) Tool Guard
        String toolGuardReason = toolGuardService.check(toolName, input);
        if (toolGuardReason != null) {
            log.warn("[tool-guard] blocked tool '{}' ({})", toolName, toolGuardReason);
            acting.setToolUse(reject(toolUse, toolGuardReason));
            return Mono.just(event);
        }

        // 2) File Guard (workspace containment)
        Path workspaceRoot = WorkspaceContext.get();
        String fileGuardReason = fileGuardService.check(toolName, input, workspaceRoot);
        if (fileGuardReason != null) {
            log.warn("[file-guard] blocked tool '{}' ({})", toolName, fileGuardReason);
            acting.setToolUse(reject(toolUse, fileGuardReason));
            return Mono.just(event);
        }

        // 3) Approval (delegated so a single hook registration keeps both
        //    layers — guard first, then human approval)
        return approvalHook.onEvent(event);
    }

    @Override
    public int priority() {
        return 10;
    }

    private static ToolUseBlock reject(ToolUseBlock toolUse, String message) {
        return ToolUseBlock.builder()
                .id(toolUse.getId())
                .name(toolUse.getName())
                .input(toolUse.getInput())
                .content(message)
                .state(ToolCallState.FINISHED)
                .build();
    }
}
