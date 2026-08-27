package com.agent.coding.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tests the chat-attachment upload + preview pair: files land in the uploads
 * media directory and are streamed back through /files/preview.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsoleUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private Path storedPath(MvcResult result) throws Exception {
        String url = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.url");
        return Path.of(url);
    }

    @Test
    void uploadRequiresFile() throws Exception {
        mockMvc.perform(multipart("/api/console/upload")
                        .file(new MockMultipartFile("other", "x.txt", "text/plain", "x".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadStoresFileAndReturnsUrl() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/console/upload")
                        .file(new MockMultipartFile("file", "hello world.txt",
                                "text/plain", "hi there".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isString())
                .andExpect(jsonPath("$.file_name").value("hello world.txt"))
                .andExpect(jsonPath("$.stored_name").isString())
                .andReturn();

        Path stored = storedPath(result);
        assertTrue(Files.isRegularFile(stored), "stored file should exist");
        assertEquals("hi there", Files.readString(stored));
        Files.deleteIfExists(stored);
    }

    @Test
    void previewStreamsStoredFile() throws Exception {
        byte[] payload = "preview me".getBytes();
        MvcResult result = mockMvc.perform(multipart("/api/console/upload")
                        .file(new MockMultipartFile("file", "note.txt",
                                "text/plain", payload)))
                .andExpect(status().isOk())
                .andReturn();
        Path stored = storedPath(result);
        String name = stored.getFileName().toString();
        try {
            mockMvc.perform(get("/api/files/preview/" + name))
                    .andExpect(status().isOk())
                    .andExpect(r -> assertEquals("preview me",
                            r.getResponse().getContentAsString()));
        } finally {
            Files.deleteIfExists(stored);
        }
    }

    @Test
    void previewRejectsPathTraversal() throws Exception {
        mockMvc.perform(get("/api/files/preview/..%2F..%2Fagents.json"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void previewMissingReturns404() throws Exception {
        mockMvc.perform(get("/api/files/preview/definitely-missing-123.txt"))
                .andExpect(status().isNotFound());
    }
}
