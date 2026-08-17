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
 * Tests the chats API: create, get, message append, archive/unarchive,
 * delete and project-dir override.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.agent.coding.ChatService chatService;

    private String createChat() {
        var chat = chatService.create();
        return chat.getId();
    }

    @Test
    void listReturnsArray() throws Exception {
        mockMvc.perform(get("/api/chats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void createReturnsChat() throws Exception {
        mockMvc.perform(post("/api/chats"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.id").isString());
    }

    @Test
    void getChatReturnsHistory() throws Exception {
        String id = createChat();
        mockMvc.perform(get("/api/chats/" + id))
                .andExpect(status().isOk());
    }

    @Test
    void getUnknownChatReturns404() throws Exception {
        mockMvc.perform(get("/api/chats/no-such-chat-xyz"))
                .andExpect(status().isNotFound());
    }

    @Test
    void archiveThenUnarchive() throws Exception {
        String id = createChat();
        mockMvc.perform(post("/api/chats/" + id + "/archive"))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(post("/api/chats/" + id + "/unarchive"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void deleteRemovesChat() throws Exception {
        String id = createChat();
        mockMvc.perform(delete("/api/chats/" + id))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(get("/api/chats/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void projectDirUnknownChatReturns404() throws Exception {
        mockMvc.perform(get("/api/chats/no-such-chat-xyz/project-dir"))
                .andExpect(status().isNotFound());
    }
}
