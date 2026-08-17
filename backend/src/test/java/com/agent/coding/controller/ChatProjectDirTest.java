package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the session project-directory override endpoints
 * (/chats/{id}/project-dir) with session > agent > workspace precedence.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatProjectDirTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.agent.coding.ChatService chatService;

    private String createChat() {
        var chat = chatService.create();
        return chat.getId();
    }

    @Test
    void getReturnsWorkspaceFallback() throws Exception {
        String id = createChat();
        mockMvc.perform(get("/api/chats/" + id + "/project-dir"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project_dir").isString())
                .andExpect(jsonPath("$.source").value("workspace_fallback"))
                .andExpect(jsonPath("$.exists").isBoolean());
    }

    @Test
    void setThenGetReturnsSessionOverride() throws Exception {
        String id = createChat();
        Path tmp = Files.createTempDirectory("majo-projdir");
        mockMvc.perform(put("/api/chats/" + id + "/project-dir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project_dir\": \"" + tmp.toString().replace("\\", "/") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("session"))
                .andExpect(jsonPath("$.exists").value(true));
    }

    @Test
    void setNonexistentDirReturns400() throws Exception {
        String id = createChat();
        mockMvc.perform(put("/api/chats/" + id + "/project-dir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project_dir\": \"/nonexistent/dir/xyz\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clearRestoresFallback() throws Exception {
        String id = createChat();
        Path tmp = Files.createTempDirectory("majo-projdir-clear");
        mockMvc.perform(put("/api/chats/" + id + "/project-dir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project_dir\": \"" + tmp.toString().replace("\\", "/") + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/chats/" + id + "/project-dir"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("workspace_fallback"));
    }

    @Test
    void unknownChatReturns404() throws Exception {
        mockMvc.perform(get("/api/chats/no-such-chat-id/project-dir"))
                .andExpect(status().isNotFound());
    }
}
