package com.agent.coding.tool;

import com.agent.coding.entity.TokenUsageEntity;
import com.agent.coding.repository.TokenUsageRepository;
import io.agentscope.core.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Report today's LLM token usage from the token-usage store.
 */
@Component
public class TokenUsageTool {

    private final TokenUsageRepository repo;

    public TokenUsageTool(TokenUsageRepository repo) {
        this.repo = repo;
    }

    @Tool(name = "get_token_usage", description = "获取今天的 LLM token 用量统计")
    public String getTokenUsage() {
        String today = LocalDate.now().toString();
        java.util.List<TokenUsageEntity> records = repo.findByDateRange(today, today);
        long prompt = 0, completion = 0, calls = 0;
        var byModel = new java.util.LinkedHashMap<String, long[]>();
        for (TokenUsageEntity r : records) {
            prompt += r.getPromptTokens();
            completion += r.getCompletionTokens();
            calls += r.getCallCount();
            String key = r.getProviderId().isBlank() ? r.getModel() : r.getProviderId() + ":" + r.getModel();
            long[] acc = byModel.computeIfAbsent(key, k -> new long[3]);
            acc[0] += r.getPromptTokens();
            acc[1] += r.getCompletionTokens();
            acc[2] += r.getCallCount();
        }
        StringBuilder sb = new StringBuilder("今日(" + today + ") token 用量:\n");
        sb.append("  总计: input=").append(prompt).append(", output=").append(completion)
          .append(", 调用 ").append(calls).append(" 次");
        if (!byModel.isEmpty()) {
            for (var e : byModel.entrySet()) {
                long[] v = e.getValue();
                sb.append("\n  ").append(e.getKey()).append(": input=").append(v[0])
                  .append(", output=").append(v[1]).append(", 调用 ").append(v[2]).append(" 次");
            }
        }
        return sb.toString();
    }
}
