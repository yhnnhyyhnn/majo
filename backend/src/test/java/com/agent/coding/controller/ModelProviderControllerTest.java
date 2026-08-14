package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the model provider endpoints (ported from qwenpaw providers.py).
 * Verifies configure / test / discover / probe contract shapes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void configureUnknownModelReturns404() throws Exception {
        mockMvc.perform(put("/api/models/unknown-provider/models/unknown-model/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"max_tokens": 4096}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void probeMultimodalReturnsContract() throws Exception {
        mockMvc.perform(post("/api/models/any-provider/models/gpt-4o-vision/probe-multimodal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supports_image").isBoolean())
                .andExpect(jsonPath("$.supports_video").isBoolean())
                .andExpect(jsonPath("$.supports_multimodal").isBoolean())
                .andExpect(jsonPath("$.image_message").isString())
                .andExpect(jsonPath("$.video_message").isString());
    }

    @Test
    void testProviderWithInvalidBaseUrlFails() throws Exception {
        mockMvc.perform(post("/api/models/nonexistent/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"base_url": "http://127.0.0.1:1", "api_key": "x"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").isBoolean())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void discoverWithInvalidBaseUrlReturnsError() throws Exception {
        mockMvc.perform(post("/api/models/nonexistent/discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"base_url": "http://127.0.0.1:1", "api_key": "x"}""")
                        .param("save", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").isBoolean())
                .andExpect(jsonPath("$.models").isArray())
                .andExpect(jsonPath("$.added_count").isNumber());
    }
}
