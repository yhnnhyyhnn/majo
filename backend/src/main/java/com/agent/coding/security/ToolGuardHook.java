package com.agent.coding.security;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.approval.ApprovalHook;
import com.agent.coding.tool.MediaPromotionHook;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
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
 * Composite agent hook, wired into every HarnessAgent build. Routes events:
 * <ul>
 *   <li>PRE_ACTING → {@link ToolGuardService} + {@link FileGuardService} +
 *       {@link ApprovalHook} (security gate, then human approval);</li>
 *   <li>POST_ACTING → {@link MediaPromotionHook} (promote tool images into
 *       the multimodal context).</li>
 * </ul>
 */
@Component
public class ToolGuardHook implements Hook {

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
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // Media promotion (after tool execution)
        if (event instanceof PostActingEvent) {
            return mediaPromotionHook.onEvent(event);
        }
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
