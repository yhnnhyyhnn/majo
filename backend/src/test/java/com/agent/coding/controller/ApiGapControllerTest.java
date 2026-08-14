package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the six endpoints added to close the qwenpaw 2.1.0b1 API gap:
 * approval list, git revert, llm-routing GET, agent-status, loops gate
 * catalog and messages send.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiGapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void approvalListReturnsPendingArray() throws Exception {
        mockMvc.perform(get("/api/approval/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending_approvals").isArray())
                .andExpect(jsonPath("$.count").isNumber());
    }

    @Test
    void gateCatalogReturnsSevenGates() throws Exception {
        mockMvc.perform(get("/api/loops/gates/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].type").value("iteration"))
                .andExpect(jsonPath("$[5].exclusive_group").value("completion_rubric"));
    }

    @Test
    void agentStatusUnknownAgentReturns404() throws Exception {
        mockMvc.perform(get("/api/agents/nonexistent-agent-xyz/agent-status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentStatusDefaultAgentReturnsOk() throws Exception {
        mockMvc.perform(get("/api/agents/default/agent-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").isString())
                .andExpect(jsonPath("$.running_task_count").isNumber());
    }

    @Test
    void llmRoutingGetReturnsEmptyForDefault() throws Exception {
        mockMvc.perform(get("/api/agents/default/config/agents/llm-routing"))
                .andExpect(status().isOk());
    }

    @Test
    void gitRevertRequiresHash() throws Exception {
        mockMvc.perform(post("/api/workspace/git/revert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void messagesSendConsoleDelivers() throws Exception {
        mockMvc.perform(post("/api/messages/send")
                        .header("X-Agent-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"console\",\"target_user\":\"u1\",\"target_session\":\"s1\",\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void messagesSendUnknownChannelReturns404() throws Exception {
        mockMvc.perform(post("/api/messages/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"dingtalk\",\"target_user\":\"u1\",\"target_session\":\"s1\",\"text\":\"hello\"}"))
                .andExpect(status().isNotFound());
    }
}
