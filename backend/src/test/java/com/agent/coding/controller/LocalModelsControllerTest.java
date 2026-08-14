package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the local-models endpoints (ported from qwenpaw
 * app/routers/local_models.py). Verifies the server-status probe, model
 * listing, and lifecycle states without requiring llama.cpp.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocalModelsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void configReturnsEnabledFlag() throws Exception {
        mockMvc.perform(get("/api/local-models/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").isBoolean())
                .andExpect(jsonPath("$.models_dir").isString());
    }

    @Test
    void serverStatusHasAllContractFields() throws Exception {
        mockMvc.perform(get("/api/local-models/server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").isBoolean())
                .andExpect(jsonPath("$.installable").isBoolean())
                .andExpect(jsonPath("$.installed").isBoolean())
                .andExpect(jsonPath("$.port").hasJsonPath())
                .andExpect(jsonPath("$.model_name").hasJsonPath())
                .andExpect(jsonPath("$.message").hasJsonPath());
    }

    @Test
    void modelsReturnsArray() throws Exception {
        mockMvc.perform(get("/api/local-models/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void serverUpdateStatus() throws Exception {
        mockMvc.perform(get("/api/local-models/server/update"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_update").isBoolean());
    }

    @Test
    void downloadProgressIsIdle() throws Exception {
        mockMvc.perform(get("/api/local-models/models/download"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("idle"));
    }

    @Test
    void stopServerReturnsOk() throws Exception {
        mockMvc.perform(delete("/api/local-models/server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
