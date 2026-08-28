package com.agent.coding.security;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.approval.ApprovalHook;
import com.agent.coding.tool.MediaPromotionHook;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Map;

/**
 * Composite agent hook, wired into every HarnessAgent build. Routes events:
 * <ul>
 *   <li>PRE_ACTING → {@link ToolGuardService} + {@link FileGuardService} +
 *       {@link ApprovalHook} (security gate, then human approval);</li>
 *   <li>POST_ACTING → {@link MediaPromotionHook} (promote tool images into
 *       the multimodal context).</li>
 * </ul>
 *
 * <p>Implements {@link RuntimeContextAware} so the harness injects the
 * per-run runtime context, forwarded to {@link ApprovalHook} (which needs
 * the real majo session id for agent-level approval policy).
 */
@Component
public class ToolGuardHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(ToolGuardHook.class);

    private final ToolGuardService toolGuardService;
    private final FileGuardService fileGuardService;
    private final ApprovalHook approvalHook;
    private final MediaPromotionHook mediaPromotionHook;

    public ToolGuardHook(ToolGuardService toolGuardService,
                         FileGuardService fileGuardService,
                         ApprovalHook approvalHook,
                         MediaPromotionHook mediaPromotionHook) {
        this.toolGuardService = toolGuardService;
        this.fileGuardService = fileGuardService;
        this.approvalHook = approvalHook;
        this.mediaPromotionHook = mediaPromotionHook;
    }

    @Override
    public void setRuntimeContext(RuntimeContext ctx) {
        if (ctx != null) {
            log.info("[hook] runtime context injected: sessionId={}", ctx.getSessionId());
        } else {
            log.info("[hook] runtime context unbound");
        }
        approvalHook.setRuntimeContext(ctx);
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // Media promotion (after tool execution)
        if (event instanceof PostActingEvent) {
            return mediaPromotionHook.onEvent(event);
        }
        if (!(event instanceof PreActingEvent acting)) {
            return Mono.just(event);
        }
        // Ensure the workspace ThreadLocal is set on this (tool-execution)
        // thread — the harness runs tools on its own scheduler where the
        // controller-set ThreadLocal is not visible. File tools and File
        // Guard rely on WorkspaceContext.
        try {
            String agentId = majoAgentIdOf(event);
            if (agentId != null && !agentId.isBlank()) {
                WorkspaceContext.set(com.agent.coding.agent.AgentStore.workspaceDirForAgent(agentId).toString());
            }
        } catch (Exception ignored) {
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

    /** Harness agent name is the majo agent id (set at build time). */
    private static String majoAgentIdOf(io.agentscope.core.hook.HookEvent event) {
        try {
            io.agentscope.core.agent.Agent agent = event.getAgent();
            if (agent != null && agent.getName() != null && !agent.getName().isBlank()) {
                return agent.getName();
            }
        } catch (Exception ignored) {
        }
        return "default";
    }
}
