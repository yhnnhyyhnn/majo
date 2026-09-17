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

    @Test
    void createCustomProviderAcceptsSnakeCaseKeysAndInlineApiKey() throws Exception {
        // Contract fix (+ QwenPaw #7826): the modal sends snake_case keys and
        // an inline api_key — before this, base_url/api_key were silently
        // dropped and providers landed on OpenAI defaults.
        String created = mockMvc.perform(post("/api/models/custom-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "smoke-provider", "name": "Smoke Provider",
                                 "default_base_url": "https://api.example.com/v1",
                                 "api_key": "sk-smoke-key",
                                 "chat_model": "OpenAIChatModel"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Smoke Provider"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(
                created.contains("api.example.com"), "base_url persisted: " + created);

        // Cleanup: resolve the numeric id from the providers list, then delete.
        String list = mockMvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int nameAt = list.indexOf("\"name\":\"Smoke Provider\"");
        org.junit.jupiter.api.Assertions.assertTrue(nameAt >= 0,
                () -> "created provider in list — len=" + list.length()
                        + " — tail=" + list.substring(Math.max(0, list.length() - 400)));
        var idM = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"")
                .matcher(list.substring(0, nameAt));
        String id = null;
        while (idM.find()) {
            id = idM.group(1);
        }
        org.junit.jupiter.api.Assertions.assertTrue(id != null && !id.isBlank(),
                () -> "no id found before Smoke Provider: "
                        + list.substring(Math.max(0, nameAt - 300), nameAt));
        mockMvc.perform(delete("/api/models/custom-providers/" + id))
                .andExpect(status().isOk());
    }
}
