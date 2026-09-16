package com.agent.coding.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turn-level token usage recording and per-agent aggregation (ADR-0011).
 * Boots the real context (H2 + Flyway), which also exercises the
 * V25__create_token_usage_turns migration.
 */
@SpringBootTest
class TokenUsageServiceTest {

    @Autowired
    private TokenUsageService service;

    @Test
    void recordPersistsTurnAndAggregatesPerAgent() {
        String stamp = String.valueOf(System.nanoTime());
        String agentA = "test-agent-a-" + stamp;
        String agentB = "test-agent-b-" + stamp;

        service.record(agentA, "chat-1", "prov", "model-x", 100, 50, 1200);
        service.record(agentA, "chat-2", "prov", "model-x", 200, 80, 3000);
        service.record(agentB, "chat-3", "prov", "model-y", 10, 5, 200);

        List<Map<String, Object>> stats = service.agentStatsSince(LocalDate.now().toString());

        Map<String, Object> rowA = stats.stream()
                .filter(m -> agentA.equals(m.get("agent_id")))
                .findFirst().orElse(null);
        assertNotNull(rowA, "agent A row present");
        assertEquals(300L, rowA.get("input_tokens"));
        assertEquals(130L, rowA.get("output_tokens"));
        assertEquals(2L, rowA.get("turns"));
        assertEquals(4200L, rowA.get("duration_ms"));

        Map<String, Object> rowB = stats.stream()
                .filter(m -> agentB.equals(m.get("agent_id")))
                .findFirst().orElse(null);
        assertNotNull(rowB);
        assertEquals(10L, rowB.get("input_tokens"));
        assertEquals(1L, rowB.get("turns"));
    }

    @Test
    void recordToleratesBlankAgentId() {
        service.record(" ", "chat-x", "", "m", 1, 1, 1);
        // No exception — the row lands under the "default" agent id.
        List<Map<String, Object>> stats = service.agentStatsSince(LocalDate.now().toString());
        assertTrue(stats.stream().anyMatch(m -> "default".equals(m.get("agent_id"))));
    }
}
