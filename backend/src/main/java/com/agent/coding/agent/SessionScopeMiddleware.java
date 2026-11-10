package com.agent.coding.agent;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.approval.ApprovalHook;
import com.agent.coding.security.DoomLoopGuard;
import com.agent.coding.security.FileGuardService;
import com.agent.coding.security.ToolGuardService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Session-scope middleware (ADR-0022): agentscope 2.1.0 removed
 * RuntimeContextAware — the per-run RuntimeContext now travels through the
 * middleware chain, so every session-keyed behavior lives here:
 *
 * <ul>
 *   <li>onReasoning — the request normalizer (session media strip #8010,
 *       empty-text drop #7409, PDF strip, pressure fold, memory recall);</li>
 *   <li>onActing — workspace ThreadLocal for tool threads, input coercion
 *       (#6839), MCP access policy (#0007), tool/file guards, doom loop
 *       (session-keyed), and the approval flow incl. the decision model
 *       (ADR-0021);</li>
 *   <li>onAgent — per-turn doom-loop state reset.</li>
 * </ul>
 *
 * Denials rebuild the ToolUseBlock as FINISHED and pass the rewritten list
 * to {@code next}; approvals pass the call through unchanged. The human
 * approval wait blocks the acting pipeline exactly like the 2.0.x hook did.
 */
@Component
public class SessionScopeMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SessionScopeMiddleware.class);

    private final ToolGuardService toolGuardService;
    private final FileGuardService fileGuardService;
    private final ApprovalHook approvalHook;
    private final DoomLoopGuard doomLoopGuard;
    private final ModelRequestNormalizerHook normalizer;
    private final com.agent.coding.mcp.McpToolBridge mcpToolBridge;

    public SessionScopeMiddleware(ToolGuardService toolGuardService,
                                  FileGuardService fileGuardService,
                                  ApprovalHook approvalHook,
                                  DoomLoopGuard doomLoopGuard,
                                  ModelRequestNormalizerHook normalizer,
                                  com.agent.coding.mcp.McpToolBridge mcpToolBridge) {
        this.toolGuardService = toolGuardService;
        this.fileGuardService = fileGuardService;
        this.approvalHook = approvalHook;
        this.doomLoopGuard = doomLoopGuard;
        this.normalizer = normalizer;
        this.mcpToolBridge = mcpToolBridge;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    java.util.function.Function<AgentInput, Flux<AgentEvent>> next) {
        // Turn start: clear the doom-loop pattern state for this session
        // (was the PRE_CALL hook's resetTurn).
        doomLoopGuard.reset(ctx.getSessionId());
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        java.util.function.Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (log.isInfoEnabled()) {
            StringBuilder roles = new StringBuilder();
            for (var m : input.messages()) {
                roles.append(m.getRole()).append('(')
                        .append(m.getContent().size()).append(") ");
            }
            log.info("[session-scope] onReasoning session={} msgs={} roles={}",
                    ctx.getSessionId(), input.messages().size(), roles);
        }
        List<io.agentscope.core.message.Msg> cleaned =
                normalizer.normalize(ctx.getSessionId(), agentIdOf(agent), input.messages());
        if (cleaned != null && cleaned != input.messages()) {
            return next.apply(new ReasoningInput(cleaned, input.tools(), input.options()));
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     java.util.function.Function<ActingInput, Flux<AgentEvent>> next) {
        String sessionId = ctx.getSessionId();
        String agentId = agentIdOf(agent);
        try {
            WorkspaceContext.set(com.agent.coding.agent.AgentStore.workspaceDirForAgent(agentId).toString());
        } catch (Exception ignored) {
        }

        boolean rewritten = false;
        List<ToolUseBlock> out = new ArrayList<>(input.toolCalls().size());
        for (ToolUseBlock toolUse : input.toolCalls()) {
            ToolUseBlock gated = sanitize(agent, sessionId, agentId, toolUse);
            if (gated != toolUse) {
                rewritten = true;
            }
            out.add(gated);
        }
        if (rewritten) {
            return next.apply(new ActingInput(List.copyOf(out)));
        }
        return next.apply(input);
    }

    /**
     * The per-call safety chain (mirrors the 2.0.x PreActing hook order):
     * coercion → MCP policy → tool guard → doom loop → file guard →
     * approval (MODEL decision model or human card). Returns the block to
     * execute — the original when approved, or a FINISHED rejection.
     */
    private ToolUseBlock sanitize(Agent agent, String sessionId, String agentId, ToolUseBlock toolUse) {
        try {
            return sanitizeOrThrow(agent, sessionId, agentId, toolUse);
        } catch (Exception e) {
            log.warn("[session-scope] sanitize failed: {}", e.getMessage(), e);
            return reject(toolUse, "安全链执行失败: " + e.getMessage());
        }
    }

    private ToolUseBlock sanitizeOrThrow(Agent agent, String sessionId, String agentId,
                                         ToolUseBlock toolUse) {
        String toolName = toolUse.getName();
        @SuppressWarnings("unchecked")
        Map<String, Object> input = toolUse.getInput() instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        // 0) Schema-guided input coercion (QwenPaw #6839): models sometimes
        //    emit unquoted numbers/booleans for string-typed parameters,
        //    which strict validation (notably MCP servers) rejects.
        input = coerceInput(agent, toolUse, input);

        // 0.5) MCP access policy (ADR-0007): per-tool override → per-tool
        //      default → client default.
        com.agent.coding.mcp.McpToolBridge.McpToolDecision mcpDecision =
                mcpToolBridge.resolveToolDecision(toolName);
        if (mcpDecision != null) {
            String effect = mcpDecision.effect();
            if ("deny".equals(effect)) {
                String reason = "MCP access policy for server '" + mcpDecision.clientKey()
                        + "' denied tool '" + toolName + "'";
                log.warn("[mcp-policy] blocked tool '{}' ({})", toolName, reason);
                return reject(toolUse, reason);
            }
            if ("ask".equals(effect)) {
                String decision = approvalHook.humanCardFlow(sessionId, agentId, toolUse);
                if (!"approved".equals(decision)) {
                    return reject(toolUse, decision);
                }
                return rebuildWithInput(toolUse, input);
            }
        }

        // 1) Tool Guard
        String toolGuardReason = toolGuardService.check(toolName, input);
        if (toolGuardReason != null) {
            log.warn("[tool-guard] blocked tool '{}' ({})", toolName, toolGuardReason);
            return reject(toolUse, toolGuardReason);
        }

        // 1.5) Doom-loop guard: staged denial of identical repeated calls.
        String doomReason = doomLoopGuard.check(sessionId, toolName, input);
        if (doomReason != null) {
            log.warn("[doom-loop] blocked tool '{}': {}", toolName, doomReason);
            return reject(toolUse, doomReason);
        }

        // 2) File Guard (workspace containment)
        Path workspaceRoot = WorkspaceContext.get();
        String fileGuardReason = fileGuardService.check(toolName, input, workspaceRoot);
        if (fileGuardReason != null) {
            log.warn("[file-guard] blocked tool '{}' ({})", toolName, fileGuardReason);
            return reject(toolUse, fileGuardReason);
        }

        // 3) Approval (level based: MODEL → decision model, others a card).
        return approvalHook.gateAndAwait(sessionId, agentId, toolUse);
    }

    /** Rebuild the block with the (possibly coerced) input map. */
    private static ToolUseBlock rebuildWithInput(ToolUseBlock toolUse, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id(toolUse.getId())
                .name(toolUse.getName())
                .input(input)
                .content(toolUse.getContent())
                .state(toolUse.getState())
                .build();
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceInput(Agent agent, ToolUseBlock toolUse,
                                                   Map<String, Object> input) {
        try {
            Map<String, Object> schema = null;
            if (agent != null && agent.getToolkit() != null) {
                for (io.agentscope.core.model.ToolSchema ts : agent.getToolkit().getToolSchemas()) {
                    if (toolUse.getName().equals(ts.getName())) {
                        schema = ts.getParameters();
                        break;
                    }
                }
            }
            if (schema != null) {
                var coerced = com.agent.coding.tool.ToolCallCoercion
                        .coerceStringFields(input, schema);
                if (coerced.changed()) {
                    return coerced.input();
                }
            }
        } catch (Exception ignored) {
        }
        return input;
    }

    /** majo agent id: profile name when registered, else the SDK agent id. */
    static String agentIdOf(Agent agent) {
        try {
            if (agent != null && agent.getName() != null && !agent.getName().isBlank()
                    && com.agent.coding.agent.AgentStore.hasAgent(agent.getName())) {
                return agent.getName();
            }
            if (agent != null && agent.getAgentId() != null && !agent.getAgentId().isBlank()) {
                return agent.getAgentId();
            }
        } catch (Exception ignored) {
        }
        return "default";
    }
}
