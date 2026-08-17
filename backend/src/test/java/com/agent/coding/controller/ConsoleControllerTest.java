package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the console utility endpoints: version, health, agent-stats,
 * token-usage, inbox events and push messages.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void versionReturnsString() throws Exception {
        mockMvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isString());
    }

    @Test
    void healthzReturnsOk() throws Exception {
        mockMvc.perform(get("/api/healthz"))
                .andExpect(status().isOk());
    }

    @Test
    void agentStatsReturnsSummary() throws Exception {
        mockMvc.perform(get("/api/agent-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start_date").isString())
                .andExpect(jsonPath("$.end_date").isString());
    }

    @Test
    void tokenUsageReturnsSummary() throws Exception {
        mockMvc.perform(get("/api/token-usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_prompt_tokens").isNumber())
                .andExpect(jsonPath("$.total_completion_tokens").isNumber())
                .andExpect(jsonPath("$.total_calls").isNumber());
    }

    @Test
    void tokenUsageDetailsReturnsArray() throws Exception {
        mockMvc.perform(get("/api/token-usage/details")
                        .param("start_date", "2026-01-01")
                        .param("end_date", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void inboxEventsReturnsShape() throws Exception {
        mockMvc.perform(get("/api/console/inbox/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray());
    }

    @Test
    void pushMessagesReturnsShape() throws Exception {
        mockMvc.perform(get("/api/console/push-messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray());
    }
}
