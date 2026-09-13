package com.agent.coding.agent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for request-time context compaction (#7521/#7331/#6456 ports). */
class ContextCompactorTest {

    private static Msg userMsg(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static Msg assistantWithThinking(String thinking, String text) {
        List<ContentBlock> content = new ArrayList<>();
        content.add(ThinkingBlock.builder().thinking(thinking).build());
        content.add(TextBlock.builder().text(text).build());
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT).content(content).build();
    }

    private static Msg toolResult(String text) {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(ToolResultBlock.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static String bigText(int chars) {
        return "x".repeat(chars);
    }

    // ── Tool result bounding ─────────────────────────────────────────

    @Test
    void oversizedToolResultIsBoundedWithHeadAndTail() {
        Msg msg = toolResult(bigText(ContextCompactor.TOOL_RESULT_MAX_CHARS + 5_000));
        Msg bounded = ContextCompactor.boundToolResults(msg);
        assertTrue(bounded != msg, "oversized result must be rebuilt");

        ToolResultBlock trb = (ToolResultBlock) bounded.getContent().get(0);
        String text = ((TextBlock) trb.getOutput().get(0)).getText();
        assertTrue(text.length() < ContextCompactor.TOOL_RESULT_MAX_CHARS);
        assertTrue(text.contains(ContextCompactor.TOOL_RESULT_TRUNCATION_NOTICE));
        assertTrue(text.contains("[total " + (ContextCompactor.TOOL_RESULT_MAX_CHARS + 5_000) + " chars]"));
    }

    @Test
    void normalToolResultIsUntouched() {
        Msg msg = toolResult("short output");
        assertSame(msg, ContextCompactor.boundToolResults(msg));
    }

    @Test
    void userTextIsNeverBounded() {
        Msg msg = userMsg(bigText(50_000));
        assertSame(msg, ContextCompactor.boundToolResults(msg));
    }

    // ── Pressure folding ─────────────────────────────────────────────

    @Test
    void oldThinkingFoldsAndLastAssistantKeepsReasoning() {
        List<Msg> msgs = new ArrayList<>();
        msgs.add(userMsg("hello"));
        msgs.add(assistantWithThinking("step one reasoning", "answer one"));
        msgs.add(userMsg("more"));
        msgs.add(assistantWithThinking("latest reasoning", "answer two"));

        List<Msg> folded = ContextCompactor.foldForPressure(msgs);

        // First assistant message: thinking replaced by placeholder.
        ContentBlock firstBlock = folded.get(1).getContent().get(0);
        assertTrue(firstBlock instanceof TextBlock);
        assertEquals(ContextCompactor.THINKING_FOLD_PLACEHOLDER, ((TextBlock) firstBlock).getText());
        // Last assistant message keeps its thinking.
        assertTrue(folded.get(3).getContent().get(0) instanceof ThinkingBlock);
        assertEquals("latest reasoning", ((ThinkingBlock) folded.get(3).getContent().get(0)).getThinking());
    }

    @Test
    void oldImagesFoldAndRecentTwoImageMessagesAreKept() {
        List<Msg> msgs = new ArrayList<>();
        Msg withImage = Msg.builder().name("user").role(MsgRole.USER)
                .content(ImageBlock.builder().source(io.agentscope.core.message.URLSource.builder().url("file:///tmp/test.png").mimeType("image/png").build()).build()).build();
        msgs.add(withImage);              // old → folded
        msgs.add(userMsg("middle"));
        msgs.add(withImage);              // recent (2nd newest image msg) → kept
        msgs.add(withImage);              // newest image msg → kept
        msgs.add(userMsg("tail"));

        List<Msg> folded = ContextCompactor.foldForPressure(msgs);

        assertTrue(folded != msgs, "old images must trigger folding");
        assertEquals(ContextCompactor.IMAGE_FOLD_PLACEHOLDER,
                ((TextBlock) folded.get(0).getContent().get(0)).getText());
        assertTrue(folded.get(2).getContent().get(0) instanceof ImageBlock);
        assertTrue(folded.get(3).getContent().get(0) instanceof ImageBlock);
    }

    @Test
    void imagesInsideOldToolResultsFold() {
        List<ContentBlock> output = new ArrayList<>();
        output.add(TextBlock.builder().text("screenshot").build());
        output.add(ImageBlock.builder().source(io.agentscope.core.message.URLSource.builder().url("file:///tmp/test.png").mimeType("image/png").build()).build());
        List<Msg> msgs = new ArrayList<>();
        msgs.add(userMsg("look"));
        msgs.add(Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(ToolResultBlock.of(output)).build());
        msgs.add(userMsg("thanks"));
        // Two newer image-bearing messages push the tool result out of the
        // KEEP_RECENT_IMAGE_MESSAGES window, so its image folds.
        msgs.add(Msg.builder().name("user").role(MsgRole.USER)
                .content(ImageBlock.builder().source(io.agentscope.core.message.URLSource.builder().url("file:///tmp/b.png").mimeType("image/png").build()).build()).build());
        msgs.add(Msg.builder().name("user").role(MsgRole.USER)
                .content(ImageBlock.builder().source(io.agentscope.core.message.URLSource.builder().url("file:///tmp/c.png").mimeType("image/png").build()).build()).build());

        List<Msg> folded = ContextCompactor.foldForPressure(msgs);
        ToolResultBlock trb = (ToolResultBlock) folded.get(1).getContent().get(0);
        assertTrue(trb.getOutput().get(1) instanceof TextBlock);
        assertEquals(ContextCompactor.IMAGE_FOLD_PLACEHOLDER, ((TextBlock) trb.getOutput().get(1)).getText());
    }

    @Test
    void foldIsNoOpWhenNothingOldExists() {
        List<Msg> msgs = new ArrayList<>();
        msgs.add(userMsg("hello"));
        msgs.add(assistantWithThinking("fresh reasoning", "answer"));
        assertSame(msgs, ContextCompactor.foldForPressure(msgs));
    }

    // ── Estimation ───────────────────────────────────────────────────

    @Test
    void estimateTokensScalesWithContent() {
        List<Msg> small = List.of(userMsg("abcd")); // 1 token by chars/4
        List<Msg> big = List.of(userMsg(bigText(40_000)));
        assertTrue(ContextCompactor.estimateTokens(small) > 0);
        assertTrue(ContextCompactor.estimateTokens(big) > 9_000);
        assertEquals(0, ContextCompactor.estimateTokens(List.of()));
        assertNull(null);
        assertFalse(ContextCompactor.estimateTokens(null) < 0);
    }
}
