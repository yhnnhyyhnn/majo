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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests Settings, Envs and SecurityConfig controllers: settings CRUD,
 * offload-policy, env vars, tool-guard/sandbox/file-guard/skill-scanner.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettingsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void settingsGetReturnsValues() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").isString())
                .andExpect(jsonPath("$.baseUrl").isString());
    }

    @Test
    void settingsPostAcceptsModel() throws Exception {
        mockMvc.perform(post("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\": \"test-model\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("test-model"));
    }

    @Test
    void offloadPolicyGetReturnsValue() throws Exception {
        mockMvc.perform(get("/api/settings/offload-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.default_action").isString());
    }

    @Test
    void offloadPolicyPutRoundTrip() throws Exception {
        mockMvc.perform(put("/api/settings/offload-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"default_action\": \"offload\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.default_action").value("offload"));
    }

    @Test
    void envsListReturnsArray() throws Exception {
        mockMvc.perform(get("/api/envs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void envsPutAddsKey() throws Exception {
        String key = "MAJO_TEST_ENV_" + System.currentTimeMillis();
        mockMvc.perform(put("/api/envs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\": \"" + key + "\", \"value\": \"v1\"}"))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(delete("/api/envs/" + key))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void toolGuardGetReturnsConfig() throws Exception {
        mockMvc.perform(get("/api/config/security/tool-guard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").isBoolean());
    }

    @Test
    void toolGuardBuiltinRulesReturnsArray() throws Exception {
        mockMvc.perform(get("/api/config/security/tool-guard/builtin-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void sandboxGetReturnsConfig() throws Exception {
        mockMvc.perform(get("/api/config/security/sandbox"))
                .andExpect(status().isOk());
    }

    @Test
    void fileGuardGetReturnsConfig() throws Exception {
        mockMvc.perform(get("/api/config/security/file-guard"))
                .andExpect(status().isOk());
    }

    @Test
    void skillScannerGetReturnsConfig() throws Exception {
        mockMvc.perform(get("/api/config/security/skill-scanner"))
                .andExpect(status().isOk());
    }

    @Test
    void allowNoAuthHostsGetReturnsConfig() throws Exception {
        mockMvc.perform(get("/api/config/security/allow-no-auth-hosts"))
                .andExpect(status().isOk());
    }
}
