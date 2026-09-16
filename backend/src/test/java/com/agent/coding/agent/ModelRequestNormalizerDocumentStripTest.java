package com.agent.coding.agent;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF-in-tool-result stripping (QwenPaw #7636 port): document blocks
 * nested in tool outputs are removed unconditionally — chat-completions
 * servers reject the resulting "file" parts even on multimodal models —
 * while sibling media blocks and user-supplied document blocks survive
 * this pass (the latter are handled by the multimodal-conditional path).
 */
class ModelRequestNormalizerDocumentStripTest {

    private static DataBlock pdfBlock() {
        return DataBlock.builder()
                .source(new Base64Source("application/pdf", "JVBERi0="))
                .build();
    }

    private static DataBlock imageDataBlock() {
        return DataBlock.builder()
                .source(new Base64Source("image/png", "iVBOR"))
                .build();
    }

    @Test
    void stripsPdfInsideToolResultAndKeepsSiblingMedia() {
        ToolResultBlock trb = ToolResultBlock.of(List.of(
                TextBlock.builder().text("report attached").build(),
                pdfBlock(),
                imageDataBlock()));
        Msg msg = Msg.builder()
                .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                .content(List.of(trb))
                .build();

        Msg out = ModelRequestNormalizerHook.stripToolResultDocuments(msg);
        assertFalse(out == msg, "message must be rebuilt");

        ToolResultBlock cleaned = (ToolResultBlock) out.getContent().get(0);
        List<ContentBlock> blocks = cleaned.getOutput();
        assertTrue(blocks.stream().anyMatch(b -> b instanceof TextBlock t
                && t.getText().contains("report attached")));
        assertTrue(blocks.stream().noneMatch(b -> b instanceof DataBlock d
                && "application/pdf".equals(((Base64Source) d.getSource()).getMediaType())),
                "PDF gone");
        assertTrue(blocks.stream().anyMatch(b -> b instanceof DataBlock d
                && "image/png".equals(((Base64Source) d.getSource()).getMediaType())),
                "image preserved");
    }

    @Test
    void pdfOnlyToolResultGetsReadablePlaceholder() {
        ToolResultBlock trb = ToolResultBlock.of(List.of(pdfBlock()));
        Msg msg = Msg.builder()
                .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                .content(List.of(trb))
                .build();

        Msg out = ModelRequestNormalizerHook.stripToolResultDocuments(msg);
        ToolResultBlock cleaned = (ToolResultBlock) out.getContent().get(0);
        assertTrue(cleaned.getOutput().size() == 1
                && cleaned.getOutput().get(0) instanceof TextBlock t
                && t.getText().contains("[PDF document removed"));
    }

    @Test
    void userSuppliedPdfOutsideToolResultsUntouched() {
        Msg msg = Msg.builder()
                .role(io.agentscope.core.message.MsgRole.USER)
                .content(List.of(TextBlock.builder().text("see doc").build(), pdfBlock()))
                .build();
        assertSame(msg, ModelRequestNormalizerHook.stripToolResultDocuments(msg));
    }

    @Test
    void imageOnlyToolResultPassesThroughUnchanged() {
        ToolResultBlock trb = ToolResultBlock.of(List.of(
                ImageBlock.builder()
                        .source(new Base64Source("image/png", "iVBOR"))
                        .build()));
        Msg msg = Msg.builder()
                .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                .content(List.of(trb))
                .build();
        assertSame(msg, ModelRequestNormalizerHook.stripToolResultDocuments(msg));
    }
}
