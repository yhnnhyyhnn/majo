package com.agent.coding.agent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Empty assistant text-block stripping (QwenPaw #7409 port): a
 * reasoning-only turn persists as an empty text block and some providers
 * (Volcengine Ark) reject its replay with 400. Empty blocks are stripped
 * when non-empty siblings remain; a message made solely of empty text
 * blocks is dropped entirely.
 */
class EmptyAssistantTextBlockStripTest {

    private static Msg assistant(List<ContentBlock> content) {
        return Msg.builder()
                .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                .content(content)
                .build();
    }

    private static TextBlock text(String t) {
        return TextBlock.builder().text(t).build();
    }

    @Test
    void dropsWholeMessageMadeOnlyOfEmptyTextBlocks() {
        Msg msg = assistant(List.of(text(""), text("  ")));
        assertNull(ModelRequestNormalizerHook.dropEmptyAssistantTextBlocks(msg),
                "message with only empty text blocks must be dropped");
    }

    @Test
    void stripsEmptyBlocksWhenSiblingsRemain() {
        Msg msg = assistant(List.of(
                text(""),
                text("hmm"),
                text("real answer")));

        Msg out = ModelRequestNormalizerHook.dropEmptyAssistantTextBlocks(msg);

        assertEquals(2, out.getContent().size());
        assertFalse(out.getContent().stream().anyMatch(b -> b instanceof TextBlock t
                && t.getText().isBlank()));
    }

    @Test
    void keepsRealContentUntouched() {
        Msg msg = assistant(List.of(text("real")));
        assertSame(msg, ModelRequestNormalizerHook.dropEmptyAssistantTextBlocks(msg));
    }

    @Test
    void nonAssistantMessagesPassThrough() {
        Msg user = Msg.builder()
                .role(io.agentscope.core.message.MsgRole.USER)
                .content(List.of(text("")))
                .build();
        assertSame(user, ModelRequestNormalizerHook.dropEmptyAssistantTextBlocks(user));
    }
}
