package com.agent.coding.memory;

import com.agent.coding.service.ModelRoutingService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * LLM-backed {@link MemorySummarizer} (ADR-0014): one small call against
 * the agent's effective model, mirroring QwenPaw's ReMe auto_memory
 * extraction. Any failure propagates — the summary backend falls back to
 * the raw excerpt so a turn is never lost.
 */
@Component
public class LlmMemorySummarizer implements MemorySummarizer {

    private static final Logger log = LoggerFactory.getLogger(LlmMemorySummarizer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_SUMMARY_CHARS = 2000;
    private static final String SYSTEM_PROMPT = """
            你是记忆策展人。从下面的对话摘录中提取"值得跨会话长期记住"的内容:
            用户偏好、项目事实、环境信息、明确的决定。\
            忽略过程性内容(工具输出、中间推理、寒暄)。\
            输出为简短的条目列表(每行一条, 不超过 10 条); 若没有任何值得记住的内容, 只输出"无"。""";

    private final ModelRoutingService modelRouting;

    public LlmMemorySummarizer(ModelRoutingService modelRouting) {
        this.modelRouting = modelRouting;
    }

    @Override
    public Optional<String> summarize(String agentId, String content) {
        var slot = modelRouting.resolveEffectiveModel(agentId);
        if (slot == null || !slot.hasBoth()) {
            return Optional.empty();
        }
        OpenAIChatModel model = modelRouting.buildOpenAIChatModel(slot.providerId(), slot.modelId());
        List<Msg> msgs = List.of(
                Msg.builder().role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(SYSTEM_PROMPT).build()).build(),
                Msg.builder().role(MsgRole.USER)
                        .content(TextBlock.builder().text(content).build()).build());
        var response = model.stream(msgs, List.of(), null)
                .blockLast(TIMEOUT);
        String text = extractText(response);
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty() || "无".equals(trimmed)) {
            return Optional.empty();
        }
        if (trimmed.length() > MAX_SUMMARY_CHARS) {
            trimmed = trimmed.substring(0, MAX_SUMMARY_CHARS) + "\n…[摘要过长已截断]";
        }
        log.debug("[memory-summary] extracted {} chars for agent '{}'", trimmed.length(), agentId);
        return Optional.of(trimmed);
    }

    private static String extractText(io.agentscope.core.model.ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (var block : response.getContent()) {
            if (block instanceof TextBlock t && t.getText() != null) {
                sb.append(t.getText());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
