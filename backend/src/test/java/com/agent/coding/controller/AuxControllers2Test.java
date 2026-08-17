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
 * Tests more auxiliary controllers: plugins, backups, coding-mode, auth,
 * settings-language and market.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuxControllers2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pluginsListReturnsArray() throws Exception {
        mockMvc.perform(get("/api/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void pluginsCatalogReturnsObject() throws Exception {
        mockMvc.perform(get("/api/plugins/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plugins").isArray());
    }

    @Test
    void frontendPluginListReturnsArray() throws Exception {
        mockMvc.perform(get("/api/frontend_plugin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void frontendPluginUnknownFileReturns404() throws Exception {
        mockMvc.perform(get("/api/frontend_plugin/nonexistent/files/x.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void backupsListReturnsArray() throws Exception {
        mockMvc.perform(get("/api/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void backupUnknownReturns404() throws Exception {
        mockMvc.perform(get("/api/backups/no-such-backup"))
                .andExpect(status().isNotFound());
    }

    @Test
    void codingModeGetReturnsObject() throws Exception {
        mockMvc.perform(get("/api/coding-mode"))
                .andExpect(status().isOk());
    }

    @Test
    void settingsLanguageGetReturnsObject() throws Exception {
        mockMvc.perform(get("/api/settings/language"))
                .andExpect(status().isOk());
    }

    @Test
    void authStatusReturnsObject() throws Exception {
        mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk());
    }

    @Test
    void marketSearchReturnsObject() throws Exception {
        mockMvc.perform(post("/api/market/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"test\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void marketProvidersReturnsArray() throws Exception {
        mockMvc.perform(get("/api/market/providers"))
                .andExpect(status().isOk());
    }

    @Test
    void healthzReturnsOk() throws Exception {
        mockMvc.perform(get("/api/healthz"))
                .andExpect(status().isOk());
    }
}
