package com.agent.coding.security;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.approval.ApprovalHook;
import com.agent.coding.tool.MediaPromotionHook;
import com.agent.coding.tool.ToolCallCoercion;
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
    private final com.agent.coding.agent.ModelRequestNormalizerHook modelRequestNormalizerHook;
    private final com.agent.coding.mcp.McpToolBridge mcpToolBridge;
    private final com.agent.coding.memory.MemoryWritePipeline memoryWritePipeline;
    private final com.agent.coding.agent.CodingModePromptInjector codingModePromptInjector;

    public ToolGuardHook(ToolGuardService toolGuardService,
                         FileGuardService fileGuardService,
                         ApprovalHook approvalHook,
                         MediaPromotionHook mediaPromotionHook,
                         com.agent.coding.agent.ModelRequestNormalizerHook modelRequestNormalizerHook,
                         com.agent.coding.mcp.McpToolBridge mcpToolBridge,
                         com.agent.coding.memory.MemoryWritePipeline memoryWritePipeline,
                         com.agent.coding.agent.CodingModePromptInjector codingModePromptInjector) {
        this.toolGuardService = toolGuardService;
        this.fileGuardService = fileGuardService;
        this.approvalHook = approvalHook;
        this.mediaPromotionHook = mediaPromotionHook;
        this.modelRequestNormalizerHook = modelRequestNormalizerHook;
        this.mcpToolBridge = mcpToolBridge;
        this.memoryWritePipeline = memoryWritePipeline;
        this.codingModePromptInjector = codingModePromptInjector;
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
        // Memory write pipeline (ADR-0009): turn capture on PRE_CALL, flush
        // + prompt-file hot injection lifecycle around the call.
        if (event instanceof io.agentscope.core.hook.PreCallEvent preCall) {
            memoryWritePipeline.onPreCall(preCall);
            codingModePromptInjector.onPreCall(preCall);
            return Mono.just(event);
        }
        if (event instanceof io.agentscope.core.hook.PostCallEvent postCall) {
            memoryWritePipeline.onPostCall(postCall);
            return Mono.just(event);
        }
        // Media promotion (after tool execution)
        if (event instanceof PostActingEvent) {
            return mediaPromotionHook.onEvent(event);
        }
        // Media stripping for text-only models (before each model call)
        if (event instanceof io.agentscope.core.hook.PreReasoningEvent) {
            return modelRequestNormalizerHook.onEvent(event);
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

        // 0) Schema-guided input coercion (QwenPaw #6839): models sometimes
        //    emit unquoted numbers/booleans for string-typed parameters,
        //    which strict validation (notably MCP servers) rejects. Repair
        //    the input before guards/approval see it, and write the repaired
        //    block back so the fixed input travels with the context.
        input = coerceInput(event, toolUse, input);

        // 0.5) MCP access policy (ADR-0007): per-tool override → per-tool
        //      default → client default. "deny" (or a card that was disabled
        //      after registration) rejects the call; "ask" runs the human
        //      approval flow; "allow" falls through to the guards below.
        com.agent.coding.mcp.McpToolBridge.McpToolDecision mcpDecision =
                mcpToolBridge.resolveToolDecision(toolName);
        if (mcpDecision != null) {
            String effect = mcpDecision.effect();
            if ("deny".equals(effect)) {
                String reason = "MCP access policy for server '" + mcpDecision.clientKey()
                        + "' denied tool '" + toolName + "'";
                log.warn("[mcp-policy] blocked tool '{}' ({})", toolName, reason);
                acting.setToolUse(reject(toolUse, reason));
                return Mono.just(event);
            }
            if ("ask".equals(effect)) {
                approvalHook.enforceApproval(acting,
                        "MCP server '" + mcpDecision.clientKey() + "' requires approval");
                return Mono.just(event);
            }
        }

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

    /**
     * Coerce the tool input against the tool's registered parameter schema.
     * No-op when the schema is unavailable or nothing needed coercion.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceInput(HookEvent event, ToolUseBlock toolUse,
                                                   Map<String, Object> input) {
        try {
            Map<String, Object> schema = null;
            if (event.getAgent() != null && event.getAgent().getToolkit() != null) {
                for (io.agentscope.core.model.ToolSchema ts : event.getAgent().getToolkit().getToolSchemas()) {
                    if (toolUse.getName().equals(ts.getName())) {
                        schema = ts.getParameters();
                        break;
                    }
                }
            }
            if (schema == null || schema.isEmpty()) {
                return input;
            }
            ToolCallCoercion.Coerced result = ToolCallCoercion.coerceStringFields(input, schema);
            if (!result.changed()) {
                return input;
            }
            log.info("[coerce] repaired tool '{}' input to match string-typed schema fields", toolUse.getName());
            acting(event).setToolUse(ToolUseBlock.builder()
                    .id(toolUse.getId())
                    .name(toolUse.getName())
                    .input(result.input())
                    .content(toolUse.getContent())
                    .metadata(toolUse.getMetadata())
                    .state(toolUse.getState())
                    .build());
            return result.input();
        } catch (Exception e) {
            log.debug("[coerce] skipped for '{}': {}", toolUse.getName(), e.getMessage());
            return input;
        }
    }

    private static PreActingEvent acting(HookEvent event) {
        return (PreActingEvent) event;
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
