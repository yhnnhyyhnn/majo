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
 * Tests the public frontend-plugin endpoints (ported from qwenpaw
 * app/routers/frontend_plugin.py). Verifies the list shape and 404 handling
 * for unknown plugins / traversal attempts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FrontendPluginControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listReturnsPluginsArray() throws Exception {
        mockMvc.perform(get("/api/frontend_plugin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void unknownPluginFileReturns404() throws Exception {
        mockMvc.perform(get("/api/frontend_plugin/nonexistent/files/index.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void traversalAttemptReturns404() throws Exception {
        mockMvc.perform(get("/api/frontend_plugin/nonexistent/files/..%2F..%2Fpom.xml"))
                .andExpect(status().isNotFound());
    }
}
