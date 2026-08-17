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
 * Tests the skills API: workspace skills, pool, builtin sources and refresh.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkillsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listSkillsReturnsArray() throws Exception {
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void workspacesReturnsObject() throws Exception {
        mockMvc.perform(get("/api/skills/workspaces"))
                .andExpect(status().isOk());
    }

    @Test
    void poolReturnsArray() throws Exception {
        mockMvc.perform(get("/api/skills/pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void builtinSourcesReturnsObject() throws Exception {
        mockMvc.perform(get("/api/skills/pool/builtin-sources"))
                .andExpect(status().isOk());
    }

    @Test
    void refreshReturnsOk() throws Exception {
        mockMvc.perform(post("/api/skills/refresh"))
                .andExpect(status().isOk());
    }

    @Test
    void poolRefreshReturnsOk() throws Exception {
        mockMvc.perform(post("/api/skills/pool/refresh"))
                .andExpect(status().isOk());
    }
}
