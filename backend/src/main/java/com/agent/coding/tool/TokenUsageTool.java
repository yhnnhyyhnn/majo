package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.entity.TokenUsageEntity;
import com.agent.coding.repository.TokenUsageRepository;
import com.agent.coding.service.TokenUsageService;
import io.agentscope.core.tool.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Report today's LLM token usage from the token-usage stores (daily
 * aggregate + per-agent turn records, ADR-0011).
 */
@Component
public class TokenUsageTool {

    private final TokenUsageRepository repo;
    private final TokenUsageService tokenUsageService;

    public TokenUsageTool(TokenUsageRepository repo, TokenUsageService tokenUsageService) {
        this.repo = repo;
        this.tokenUsageService = tokenUsageService;
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
        // Per-agent dimension (ADR-0011) — highlight the calling agent.
        String self = agentIdOf();
        List<Map<String, Object>> agents = tokenUsageService.agentStatsSince(today);
        if (!agents.isEmpty()) {
            sb.append("\n  按 agent:");
            for (Map<String, Object> a : agents) {
                sb.append("\n  ").append(a.get("agent_id"))
                  .append(self != null && self.equals(a.get("agent_id")) ? " (本 agent)" : "")
                  .append(": input=").append(a.get("input_tokens"))
                  .append(", output=").append(a.get("output_tokens"))
                  .append(", ").append(a.get("turns")).append(" 轮");
            }
        }
        return sb.toString();
    }

    private static String agentIdOf() {
        try {
            Path ws = WorkspaceContext.get();
            return ws.getFileName() != null ? ws.getFileName().toString() : "default";
        } catch (Exception e) {
            return null;
        }
    }
}
