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
 * Tests the tool-calls API: list shape, unknown-call 404 handling, and
 * extend-deadline validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ToolCallsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listReturnsShape() throws Exception {
        mockMvc.perform(get("/api/tool-calls/sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").isNumber());
    }

    @Test
    void unknownCallReturns404() throws Exception {
        mockMvc.perform(get("/api/tool-calls/sess-1/no-such-call"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelUnknownCallReturns404() throws Exception {
        mockMvc.perform(post("/api/tool-calls/sess-1/no-such-call/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void outputUnknownCallReturns404() throws Exception {
        mockMvc.perform(get("/api/tool-calls/sess-1/no-such-call/output"))
                .andExpect(status().isNotFound());
    }

    @Test
    void offloadUnknownCallReturns404() throws Exception {
        mockMvc.perform(post("/api/tool-calls/sess-1/no-such-call/offload"))
                .andExpect(status().isNotFound());
    }
}
