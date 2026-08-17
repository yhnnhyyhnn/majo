package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests auxiliary controllers: loops (catalog/custom modes), messages send,
 * commands check, agent status and settings offload-policy.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuxControllersTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loopsListReturnsModes() throws Exception {
        mockMvc.perform(get("/api/loops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").isString());
    }

    @Test
    void loopsGateCatalogReturnsGates() throws Exception {
        mockMvc.perform(get("/api/loops/gates/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].type").value("iteration"));
    }

    @Test
    void loopsCustomCrudRoundTrip() throws Exception {
        String id = "aux-mode-" + System.currentTimeMillis();
        mockMvc.perform(post("/api/loops/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": \"" + id + "\", \"name\": \"Aux Mode\", "
                                + "\"slash_command\": \"aux\", \"gates\": []}"))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(post("/api/loops/custom/" + id + "/duplicate"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void messagesSendConsoleDelivers() throws Exception {
        mockMvc.perform(post("/api/messages/send")
                        .header("X-Agent-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"console\",\"target_user\":\"u1\","
                                + "\"target_session\":\"s1\",\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void messagesSendUnknownChannelReturns404() throws Exception {
        mockMvc.perform(post("/api/messages/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"slack\",\"target_user\":\"u1\","
                                + "\"target_session\":\"s1\",\"text\":\"hi\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void commandsCheckReturnsResult() throws Exception {
        mockMvc.perform(post("/api/commands/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\": \"/clear\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void agentStatusDefaultReturnsOk() throws Exception {
        mockMvc.perform(get("/api/agents/default/agent-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").isString())
                .andExpect(jsonPath("$.running_task_count").isNumber());
    }

    @Test
    void agentStatusUnknownReturns404() throws Exception {
        mockMvc.perform(get("/api/agents/nonexistent-agent/agent-status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void offloadPolicyGetReturnsValue() throws Exception {
        mockMvc.perform(get("/api/settings/offload-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.default_action").isString());
    }

    @Test
    void offloadPolicySetRoundTrip() throws Exception {
        mockMvc.perform(put("/api/settings/offload-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"default_action\": \"offload\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.default_action").value("offload"));
        mockMvc.perform(get("/api/settings/offload-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.default_action").value("offload"));
    }
}
