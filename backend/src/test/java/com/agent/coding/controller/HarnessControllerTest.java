package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the third-party harness catalog endpoints (ported
 * from qwenpaw app/routers/harnesses.py) and pawapps management.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HarnessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listReturnsProviderCatalog() throws Exception {
        mockMvc.perform(get("/api/harnesses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.providers[0].id").value("codex"))
                .andExpect(jsonPath("$.providers[0].name").value("Codex"))
                .andExpect(jsonPath("$.providers[0].capabilities.authentication").value(true))
                .andExpect(jsonPath("$.providers[0].capabilities.approval_presets").isArray())
                .andExpect(jsonPath("$.providers[1].id").value("claude"))
                .andExpect(jsonPath("$.providers[1].coming_soon").value(true))
                .andExpect(jsonPath("$.providers[2].id").value("qoder"));
    }

    @Test
    void codexModelsReturned() throws Exception {
        mockMvc.perform(get("/api/harnesses/codex/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models").isArray())
                .andExpect(jsonPath("$.models[0].id").value("gpt-5.2-codex"))
                .andExpect(jsonPath("$.models[0].is_default").value(true));
    }

    @Test
    void claudeModelsEmptyWhenComingSoonCapabilities() throws Exception {
        mockMvc.perform(get("/api/harnesses/claude/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models").isArray());
    }

    @Test
    void unknownProviderReturns404() throws Exception {
        mockMvc.perform(get("/api/harnesses/nonexistent/models"))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusProbesProvider() throws Exception {
        mockMvc.perform(post("/api/harnesses/codex/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settings": {}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("codex"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.capabilities.model_selection").value(true));
    }

    @Test
    void logoutReturnsOk() throws Exception {
        mockMvc.perform(post("/api/harnesses/codex/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void pawappsListAndUninstallFlow() throws Exception {
        mockMvc.perform(get("/api/pawapps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apps").isArray());

        mockMvc.perform(delete("/api/pawapps/nonexistent-app"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pawappUninstallRejectsTraversal() throws Exception {
        mockMvc.perform(delete("/api/pawapps/..%2F.."))
                .andExpect(status().is4xxClientError());
    }
}
