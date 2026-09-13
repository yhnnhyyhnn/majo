package com.agent.coding.agent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Request-time context compaction, ported from QwenPaw's scroll manager
 * capabilities (thinking folding #7521, oversized tool result bounding
 * #7331, visual compact #6456). All operations work on request message
 * copies only — stored session history keeps full content.
 *
 * <p>Three mechanisms:
 * <ol>
 *   <li><b>Tool result bounding</b> — oversized tool result text blocks are
 *       replaced by a head+tail preview with a truncation notice (always
 *       applied);</li>
 *   <li><b>Thinking folding</b> — under pressure, thinking blocks of older
 *       assistant messages fold to a placeholder (the last assistant message
 *       keeps its reasoning for continuity);</li>
 *   <li><b>Visual compact</b> — under pressure, image blocks in all but the
 *       most recent image-bearing messages fold to a placeholder.</li>
 * </ol>
 */
public final class ContextCompactor {

    /** Max chars of one tool result text block in a request. */
    public static final int TOOL_RESULT_MAX_CHARS = 20_000;
    private static final int TOOL_RESULT_HEAD_CHARS = 12_000;
    private static final int TOOL_RESULT_TAIL_CHARS = 2_000;

    /** Fraction of the context window at which folding kicks in. */
    public static final double PRESSURE_RATIO = 0.7;

    /** How many of the most recent image-bearing messages keep their images. */
    public static final int KEEP_RECENT_IMAGE_MESSAGES = 2;

    private static final int CHARS_PER_TOKEN = 4;

    public static final String THINKING_FOLD_PLACEHOLDER =
            "[earlier reasoning omitted - folded to save context]";
    public static final String IMAGE_FOLD_PLACEHOLDER =
            "[earlier image omitted - folded to save context]";
    public static final String TOOL_RESULT_TRUNCATION_NOTICE =
            "\n[... output truncated in this request; the full text remains in session history ...]";

    private ContextCompactor() {}

    /** Rough request size estimate (~4 chars per token, flat cost per image). */
    public static long estimateTokens(List<Msg> msgs) {
        long chars = 0;
        if (msgs == null) {
            return 0;
        }
        for (Msg msg : msgs) {
            List<ContentBlock> content = msg.getContent();
            if (content == null) {
                continue;
            }
            for (ContentBlock block : content) {
                if (block instanceof TextBlock t) {
                    chars += t.getText() == null ? 0 : t.getText().length();
                } else if (block instanceof ThinkingBlock th) {
                    chars += th.getThinking() == null ? 0 : th.getThinking().length();
                } else if (block instanceof ToolResultBlock tr && tr.getOutput() != null) {
                    for (ContentBlock out : tr.getOutput()) {
                        if (out instanceof TextBlock t) {
                            chars += t.getText() == null ? 0 : t.getText().length();
                        }
                    }
                } else if (block instanceof ImageBlock) {
                    chars += 1024L * CHARS_PER_TOKEN; // conservative flat image cost
                }
            }
        }
        return chars / CHARS_PER_TOKEN;
    }

    /** Replace oversized tool result text with a head+tail preview. */
    public static Msg boundToolResults(Msg msg) {
        List<ContentBlock> content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return msg;
        }
        List<ContentBlock> out = new ArrayList<>(content.size());
        boolean changed = false;
        for (ContentBlock block : content) {
            if (block instanceof ToolResultBlock trb) {
                ToolResultBlock bounded = boundToolResultBlock(trb);
                if (bounded != trb) {
                    changed = true;
                }
                out.add(bounded);
                continue;
            }
            out.add(block);
        }
        return changed ? msg.withContent(out) : msg;
    }

    private static ToolResultBlock boundToolResultBlock(ToolResultBlock trb) {
        List<ContentBlock> output = trb.getOutput();
        if (output == null || output.isEmpty()) {
            return trb;
        }
        List<ContentBlock> filtered = new ArrayList<>(output.size());
        boolean changed = false;
        for (ContentBlock block : output) {
            if (block instanceof TextBlock t && t.getText() != null
                    && t.getText().length() > TOOL_RESULT_MAX_CHARS) {
                filtered.add(TextBlock.builder().text(preview(t.getText())).build());
                changed = true;
                continue;
            }
            filtered.add(block);
        }
        if (!changed) {
            return trb;
        }
        return ToolResultBlock.builder()
                .id(trb.getId())
                .name(trb.getName())
                .output(filtered)
                .metadata(trb.getMetadata())
                .state(trb.getState())
                .build();
    }

    private static String preview(String text) {
        int total = text.length();
        String head = text.substring(0, TOOL_RESULT_HEAD_CHARS);
        String tail = total > TOOL_RESULT_HEAD_CHARS + TOOL_RESULT_TAIL_CHARS
                ? text.substring(total - TOOL_RESULT_TAIL_CHARS) : "";
        return head + tail + TOOL_RESULT_TRUNCATION_NOTICE
                + "\n[total " + total + " chars]";
    }

    /**
     * Fold old thinking and images under context pressure.
     *
     * @return the folded list (same instance when nothing was folded)
     */
    public static List<Msg> foldForPressure(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return msgs;
        }
        int lastAssistant = -1;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = i;
                break;
            }
        }
        Set<Integer> keepImages = imageMessageIndexesToKeep(msgs);

        List<Msg> out = new ArrayList<>(msgs.size());
        boolean changed = false;
        for (int i = 0; i < msgs.size(); i++) {
            Msg folded = foldMessage(msgs.get(i), i == lastAssistant, keepImages.contains(i));
            if (folded != msgs.get(i)) {
                changed = true;
            }
            out.add(folded);
        }
        return changed ? out : msgs;
    }

    /** Indexes of the most recent messages allowed to keep image blocks. */
    private static Set<Integer> imageMessageIndexesToKeep(List<Msg> msgs) {
        Set<Integer> keep = new HashSet<>();
        int found = 0;
        for (int i = msgs.size() - 1; i >= 0 && found < KEEP_RECENT_IMAGE_MESSAGES; i--) {
            if (hasImage(msgs.get(i))) {
                keep.add(i);
                found++;
            }
        }
        return keep;
    }

    private static boolean hasImage(Msg msg) {
        List<ContentBlock> content = msg.getContent();
        if (content == null) {
            return false;
        }
        for (ContentBlock block : content) {
            if (block instanceof ImageBlock) {
                return true;
            }
            if (block instanceof ToolResultBlock trb && trb.getOutput() != null) {
                for (ContentBlock out : trb.getOutput()) {
                    if (out instanceof ImageBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Msg foldMessage(Msg msg, boolean isLastAssistant, boolean keepImages) {
        List<ContentBlock> content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return msg;
        }
        List<ContentBlock> out = new ArrayList<>(content.size());
        boolean changed = false;
        for (ContentBlock block : content) {
            if (!isLastAssistant && block instanceof ThinkingBlock) {
                out.add(TextBlock.builder().text(THINKING_FOLD_PLACEHOLDER).build());
                changed = true;
                continue;
            }
            if (!keepImages && block instanceof ImageBlock) {
                out.add(TextBlock.builder().text(IMAGE_FOLD_PLACEHOLDER).build());
                changed = true;
                continue;
            }
            if (!keepImages && block instanceof ToolResultBlock trb && hasImage(trb)) {
                List<ContentBlock> filtered = new ArrayList<>(trb.getOutput().size());
                boolean innerChanged = false;
                for (ContentBlock out2 : trb.getOutput()) {
                    if (out2 instanceof ImageBlock) {
                        filtered.add(TextBlock.builder().text(IMAGE_FOLD_PLACEHOLDER).build());
                        innerChanged = true;
                        continue;
                    }
                    filtered.add(out2);
                }
                if (innerChanged) {
                    changed = true;
                    out.add(rebuildResult(trb, filtered));
                    continue;
                }
            }
            out.add(block);
        }
        if (!changed) {
            return msg;
        }
        if (out.isEmpty()) {
            out.add(TextBlock.builder().text(THINKING_FOLD_PLACEHOLDER).build());
        }
        return msg.withContent(out);
    }

    private static ToolResultBlock rebuildResult(ToolResultBlock trb, List<ContentBlock> output) {
        return ToolResultBlock.builder()
                .id(trb.getId())
                .name(trb.getName())
                .output(output)
                .metadata(trb.getMetadata())
                .state(trb.getState())
                .build();
    }

    private static boolean hasImage(ToolResultBlock trb) {
        if (trb.getOutput() == null) {
            return false;
        }
        for (ContentBlock out : trb.getOutput()) {
            if (out instanceof ImageBlock) {
                return true;
            }
        }
        return false;
    }
}
