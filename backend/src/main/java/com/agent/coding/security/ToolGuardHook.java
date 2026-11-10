package com.agent.coding.security;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.approval.ApprovalHook;
import com.agent.coding.tool.MediaPromotionHook;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Map;

/**
 * Composite agent hook, wired into every HarnessAgent build (agentscope
 * 2.1.0). Under ADR-0022 the session-keyed safety chain (guards, doom loop,
 * approval, request normalization) moved to {@code SessionScopeMiddleware};
 * this hook keeps only the object-level, session-free event work:
 * <ul>
 *   <li>PRE_CALL/POST_CALL → {@link com.agent.coding.memory.MemoryWritePipeline}
 *       + {@link com.agent.coding.agent.CodingModePromptInjector};</li>
 *   <li>POST_ACTING → {@link MediaPromotionHook} (promote tool media into
 *       the multimodal context).</li>
 * </ul>
 */
@Component
public class ToolGuardHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolGuardHook.class);

    private final MediaPromotionHook mediaPromotionHook;
    private final com.agent.coding.mcp.McpToolBridge mcpToolBridge;
    private final com.agent.coding.memory.MemoryWritePipeline memoryWritePipeline;
    private final com.agent.coding.agent.CodingModePromptInjector codingModePromptInjector;

    public ToolGuardHook(MediaPromotionHook mediaPromotionHook,
                         com.agent.coding.mcp.McpToolBridge mcpToolBridge,
                         com.agent.coding.memory.MemoryWritePipeline memoryWritePipeline,
                         com.agent.coding.agent.CodingModePromptInjector codingModePromptInjector) {
        this.mediaPromotionHook = mediaPromotionHook;
        this.mcpToolBridge = mcpToolBridge;
        this.memoryWritePipeline = memoryWritePipeline;
        this.codingModePromptInjector = codingModePromptInjector;
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
        // Media promotion (after tool execution): object-level rewrite, no
        // session dependency — stays a hook under 2.1.0 (ADR-0022).
        if (event instanceof PostActingEvent) {
            return mediaPromotionHook.onEvent(event);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 10;
    }
}
