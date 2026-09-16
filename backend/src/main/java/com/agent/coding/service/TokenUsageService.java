package com.agent.coding.service;

import com.agent.coding.entity.TurnUsageEntity;
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

    public TokenUsageService(TurnUsageRepository repo) {
        this.repo = repo;
    }

    /** Persist one completed turn's usage. */
    @Transactional
    public void record(String agentId, String chatId, String providerId, String model,
                       int inputTokens, int outputTokens, long durationMs) {
        try {
            repo.save(new TurnUsageEntity(
                    agentId == null || agentId.isBlank() ? "default" : agentId,
                    chatId, providerId == null ? "" : providerId,
                    model == null ? "" : model,
                    inputTokens, outputTokens, durationMs));
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
            out.add(m);
        }
        return out;
    }
}
