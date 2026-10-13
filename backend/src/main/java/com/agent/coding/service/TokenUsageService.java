package com.agent.coding.service;

import com.agent.coding.entity.TokenUsageEntity;
import com.agent.coding.entity.TurnUsageEntity;
import com.agent.coding.repository.TokenUsageRepository;
import com.agent.coding.repository.TurnUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turn-level token usage recording and per-agent aggregation (ADR-0011),
 * the majo counterpart of QwenPaw's turn_usage/agent_stats. Recording
 * failures are logged and swallowed — usage bookkeeping must never break a
 * completed turn.
 */
@Service
public class TokenUsageService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);

    private final TurnUsageRepository repo;
    private final TokenUsageRepository dailyRepo;
    private final ModelPricingService pricing;

    public TokenUsageService(TurnUsageRepository repo, TokenUsageRepository dailyRepo,
                             ModelPricingService pricing) {
        this.repo = repo;
        this.dailyRepo = dailyRepo;
        this.pricing = pricing;
    }

    /**
     * Record one completed turn into both ledgers (ADR-0011 rows plus the
     * legacy daily table), with a cost snapshot taken now (ADR-0017) — the
     * price at time of use, immutable once written.
     */
    @Transactional
    public void recordTurnAndDaily(String agentId, String chatId, String providerId, String model,
                                   int inputTokens, int outputTokens, long durationMs) {
        try {
            String effectiveAgent = agentId == null || agentId.isBlank() ? "default" : agentId;
            String effectiveProvider = providerId == null ? "" : providerId;
            String effectiveModel = model == null ? "" : model;
            Double cost = pricing.costFor(effectiveProvider, effectiveModel, inputTokens, outputTokens);
            dailyRepo.save(new TokenUsageEntity(LocalDate.now().toString(),
                    effectiveProvider, effectiveModel, inputTokens, outputTokens, cost));
            repo.save(new TurnUsageEntity(effectiveAgent, chatId, effectiveProvider,
                    effectiveModel, inputTokens, outputTokens, durationMs, cost));
        } catch (Exception e) {
            log.warn("[token-usage] turn record failed: {}", e.getMessage());
        }
    }

    /** Per-agent totals since the given date (inclusive), most-used first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> agentStatsSince(String start) {
        LocalDateTime since = (start == null || start.isBlank())
                ? LocalDate.now().minusDays(30).atStartOfDay()
                : LocalDate.parse(start).atStartOfDay();
        List<Object[]> rows = repo.aggregateByAgentSince(since);
        List<Map<String, Object>> out = new java.util.ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agent_id", row[0]);
            m.put("input_tokens", row[1] == null ? 0L : ((Number) row[1]).longValue());
            m.put("output_tokens", row[2] == null ? 0L : ((Number) row[2]).longValue());
            m.put("turns", row[3] == null ? 0L : ((Number) row[3]).longValue());
            m.put("duration_ms", row[4] == null ? 0L : ((Number) row[4]).longValue());
            if (row.length > 5 && row[5] != null) {
                m.put("cost_usd", ((Number) row[5]).doubleValue());
            }
            out.add(m);
        }
        return out;
    }
}
