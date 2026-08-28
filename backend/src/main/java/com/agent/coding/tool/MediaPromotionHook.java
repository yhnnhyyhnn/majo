package com.agent.coding.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Promotes images returned by tools (view_image / desktop_screenshot) into
 * the conversation as a follow-up user message, so the next model call is
 * multimodal. Mirrors QwenPaw's tool-result media promotion.
 *
 * <p>Registered as part of the composite agent hook (delegated from
 * {@link com.agent.coding.security.ToolGuardHook} on PostActing events),
 * so it applies to every agent entry point without extra wiring.
 */
@Component
public class MediaPromotionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(MediaPromotionHook.class);

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostActingEvent acting)) {
            return Mono.just(event);
        }
        ToolResultBlock result = acting.getToolResult();
        if (result == null || result.getOutput() == null) {
            return Mono.just(event);
        }
        List<ImageBlock> images = new ArrayList<>();
        StringBuilder note = new StringBuilder();
        for (io.agentscope.core.message.ContentBlock block : result.getOutput()) {
            if (block instanceof ImageBlock img) {
                images.add(img);
            } else if (block instanceof TextBlock tb) {
                if (!tb.getText().isBlank()) {
                    if (!note.isEmpty()) {
                        note.append(" ");
                    }
                    note.append(tb.getText());
                }
            }
        }
        if (images.isEmpty()) {
            return Mono.just(event);
        }
        try {
            Agent agent = event.getAgent();
            AgentState state = agent != null ? agent.getAgentState() : null;
            if (state == null) {
                log.warn("[media] no agent state to promote image into");
                return Mono.just(event);
            }
            List<io.agentscope.core.message.ContentBlock> content = new ArrayList<>();
            if (!note.isEmpty()) {
                content.add(TextBlock.builder().text(note.toString()).build());
            }
            content.addAll(images);
            Msg promoted = Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .content(content)
                    .build();
            state.contextMutable().add(promoted);
            log.info("[media] promoted {} image(s) into context", images.size());
        } catch (Exception e) {
            log.warn("[media] promotion failed: {}", e.getMessage());
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 20;
    }
}
