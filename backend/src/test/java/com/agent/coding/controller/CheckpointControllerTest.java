package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the workspace checkpoint API surface: status/graph endpoints and
 * validation errors.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CheckpointControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusReturnsOk() throws Exception {
        mockMvc.perform(get("/api/workspace/checkpoints/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auto_enabled").isBoolean())
                .andExpect(jsonPath("$.has_checkpoints").isBoolean())
                .andExpect(jsonPath("$.workspace_dir").isString());
    }

    @Test
    void graphReturnsShape() throws Exception {
        mockMvc.perform(get("/api/workspace/checkpoints/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.sessions").isArray())
                .andExpect(jsonPath("$.summary.total").isNumber())
                .andExpect(jsonPath("$.truncated").isBoolean());
    }

    @Test
    void snapshotRequiresSessionId() throws Exception {
        mockMvc.perform(post("/api/workspace/checkpoints/snapshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetReturnsOk() throws Exception {
        mockMvc.perform(delete("/api/workspace/checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(true));
    }
}
