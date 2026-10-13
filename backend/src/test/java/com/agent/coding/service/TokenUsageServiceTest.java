package com.agent.coding.service;

import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.repository.ProviderRepository;
import com.agent.coding.repository.TokenUsageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turn-level token usage recording and per-agent aggregation (ADR-0011),
 * plus the daily ledger and cost snapshot (ADR-0017). Boots the real
 * context (H2 + Flyway), which also exercises the usage migrations.
 */
@SpringBootTest
class TokenUsageServiceTest {

    @Autowired
    private TokenUsageService service;

    @Autowired
    private TokenUsageRepository dailyRepo;

    @Autowired
    private ProviderRepository providerRepo;

    @Autowired
    private ProviderModelRepository providerModelRepo;

    @Test
    void recordPersistsTurnAndDailyAndAggregatesPerAgent() {
        String stamp = String.valueOf(System.nanoTime());
        String agentA = "test-agent-a-" + stamp;
        String agentB = "test-agent-b-" + stamp;

        long dailyBefore = dailyRepo.count();
        service.recordTurnAndDaily(agentA, "chat-1", "prov", "model-x", 100, 50, 1200);
        service.recordTurnAndDaily(agentA, "chat-2", "prov", "model-x", 200, 80, 3000);
        service.recordTurnAndDaily(agentB, "chat-3", "prov", "model-y", 10, 5, 200);
        // Daily ledger got one row per turn (legacy table, still populated).
        assertEquals(dailyBefore + 3, dailyRepo.count());

        List<Map<String, Object>> stats = service.agentStatsSince(LocalDate.now().toString());

        Map<String, Object> rowA = stats.stream()
                .filter(m -> agentA.equals(m.get("agent_id")))
                .findFirst().orElse(null);
        assertNotNull(rowA, "agent A row present");
        assertEquals(300L, rowA.get("input_tokens"));
        assertEquals(130L, rowA.get("output_tokens"));
        assertEquals(2L, rowA.get("turns"));
        assertEquals(4200L, rowA.get("duration_ms"));
        // No price metadata for prov/model-x → no cost invented.
        assertFalse(rowA.containsKey("cost_usd"));

        Map<String, Object> rowB = stats.stream()
                .filter(m -> agentB.equals(m.get("agent_id")))
                .findFirst().orElse(null);
        assertNotNull(rowB);
        assertEquals(10L, rowB.get("input_tokens"));
        assertEquals(1L, rowB.get("turns"));
    }

    @Test
    void recordSnapshotsCostAtRecordTime() {
        String stamp = String.valueOf(System.nanoTime());
        String providerId = "prov-priced-" + stamp;
        ProviderEntity p = new ProviderEntity();
        p.setId(providerId);
        p.setName("Priced " + stamp);
        p.setBaseUrl("https://priced.example/v1");
        p.setApiKey("sk-test");
        providerRepo.save(p);

        ProviderModelEntity m = new ProviderModelEntity();
        m.setProviderId(providerId);
        m.setModelId("model-priced");
        m.setName("Priced Model");
        m.setInputPrice(3.0);   // USD per 1M input tokens
        m.setOutputPrice(15.0); // USD per 1M output tokens
        providerModelRepo.save(m);

        String agent = "test-agent-cost-" + stamp;
        // 1M in + 0.2M out → 3.0 + 3.0 = 6.0 USD
        service.recordTurnAndDaily(agent, "chat-c", providerId, "model-priced",
                1_000_000, 200_000, 500);

        List<Map<String, Object>> stats = service.agentStatsSince(LocalDate.now().toString());
        Map<String, Object> row = stats.stream()
                .filter(s -> agent.equals(s.get("agent_id")))
                .findFirst().orElse(null);
        assertNotNull(row, "cost row present");
        assertEquals(6.0, (Double) row.get("cost_usd"), 1e-9);
    }

    @Test
    void recordToleratesBlankAgentId() {
        service.recordTurnAndDaily(" ", "chat-x", "", "m", 1, 1, 1);
        // No exception — the row lands under the "default" agent id.
        List<Map<String, Object>> stats = service.agentStatsSince(LocalDate.now().toString());
        assertTrue(stats.stream().anyMatch(m -> "default".equals(m.get("agent_id"))));
    }
}
