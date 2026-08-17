package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the multi-agent management API (/api/agents): list, get, create,
 * toggle, reorder, memory reindex and delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listReturnsAgentsArray() throws Exception {
        mockMvc.perform(get("/api/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents").isArray());
    }

    @Test
    void getDefaultAgentReturnsProfile() throws Exception {
        mockMvc.perform(get("/api/agents/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("default"));
    }

    @Test
    void unknownAgentReturns404() throws Exception {
        mockMvc.perform(get("/api/agents/no-such-agent-xyz"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createToggleDeleteRoundTrip() throws Exception {
        String id = "test-agent-" + System.currentTimeMillis();
        try {
            mockMvc.perform(post("/api/agents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\": \"" + id + "\", \"name\": \"Test Agent\"}"))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(jsonPath("$.id").value(id));

            mockMvc.perform(patch("/api/agents/" + id + "/toggle")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": false}"))
                    .andExpect(status().isOk());
        } finally {
            mockMvc.perform(delete("/api/agents/" + id))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void reorderAcceptsList() throws Exception {
        mockMvc.perform(put("/api/agents/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agent_ids\": [\"default\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void memoryReindexReturnsStats() throws Exception {
        mockMvc.perform(post("/api/agents/default/memory/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.indexed_files").isNumber())
                .andExpect(jsonPath("$.keywords").isNumber());
    }
}
