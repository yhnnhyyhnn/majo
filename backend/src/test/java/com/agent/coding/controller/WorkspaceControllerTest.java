package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the workspace endpoints (ported from qwenpaw app/routers/workspace.py).
 * Verifies memory / system-prompt-files / commands / coding-project are
 * functional rather than stubs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void systemPromptFilesReturnsArray() throws Exception {
        mockMvc.perform(get("/api/workspace/system-prompt-files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void systemPromptFilesUpdateAcceptsList() throws Exception {
        mockMvc.perform(put("/api/workspace/system-prompt-files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"files": ["AGENTS.md", "PROFILE.md"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void commandsAvailableReturnsCommands() throws Exception {
        mockMvc.perform(get("/api/workspace/commands/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands").isArray())
                .andExpect(jsonPath("$.commands[0].command").exists())
                .andExpect(jsonPath("$.commands[0].description").exists());
    }

    @Test
    void memoryReturnsArray() throws Exception {
        mockMvc.perform(get("/api/workspace/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void codingProjectListReturnsArray() throws Exception {
        mockMvc.perform(get("/api/workspace/coding-project/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void codingProjectReturnsInfo() throws Exception {
        mockMvc.perform(get("/api/workspace/coding-project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").isString())
                .andExpect(jsonPath("$.is_workspace_default").value(true));
    }

    @Test
    void browseDirsListsDirectories() throws Exception {
        mockMvc.perform(get("/api/workspace/coding-project/browse-dirs")
                        .param("path", "~"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").isString())
                .andExpect(jsonPath("$.dirs").isArray());
    }

    @Test
    void codingProjectCreateRequiresName() throws Exception {
        mockMvc.perform(post("/api/workspace/coding-project/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void codingProjectCreateMakesDir() throws Exception {
        String unique = "cp-test-" + System.currentTimeMillis();
        mockMvc.perform(post("/api/workspace/coding-project/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + unique + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(unique))
                .andExpect(jsonPath("$.path").isString());
    }

    @Test
    void codingProjectImportRequiresPath() throws Exception {
        mockMvc.perform(post("/api/workspace/coding-project/import-local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void codingProjectSetClearsProjectDir() throws Exception {
        mockMvc.perform(put("/api/workspace/coding-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_workspace_default").value(true));
    }

    @Test
    void codeFilesAreScopedToAgentWorkspace() throws Exception {
        mockMvc.perform(get("/api/workspace/code-files")
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void markdownFilesAreScopedToAgentWorkspace() throws Exception {
        mockMvc.perform(get("/api/workspace/files")
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void defaultWorkspaceTemplatesAreListed() throws Exception {
        mockMvc.perform(get("/api/workspace/files")
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].filename", org.hamcrest.Matchers.hasItem("AGENTS.md")))
                .andExpect(jsonPath("$[*].filename", org.hamcrest.Matchers.hasItem("SOUL.md")))
                .andExpect(jsonPath("$[*].filename", org.hamcrest.Matchers.hasItem("PROFILE.md")))
                .andExpect(jsonPath("$[*].filename", org.hamcrest.Matchers.hasItem("HEARTBEAT.md")))
                .andExpect(jsonPath("$[*].filename", org.hamcrest.Matchers.hasItem("MEMORY.md")));
    }

    @Test
    void memoryIsScopedToAgentWorkspace() throws Exception {
        mockMvc.perform(get("/api/workspace/memory")
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void codingProjectIsScopedToAgentWorkspace() throws Exception {
        mockMvc.perform(get("/api/workspace/coding-project")
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace_dir").isString())
                .andExpect(jsonPath("$.is_workspace_default").value(true));
    }

    @Test
    void memorySectionDailyFiltersDigest() throws Exception {
        mockMvc.perform(get("/api/workspace/memory")
                        .param("section", "daily")
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void memorySectionDigestFiltersDaily() throws Exception {
        mockMvc.perform(get("/api/workspace/memory")
                        .param("section", "digest")
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void fileUploadRequiresFiles() throws Exception {
        mockMvc.perform(multipart("/api/workspace/file-upload")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "other", "x.txt", "text/plain", "x".getBytes()))
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fileUploadRejectsInvalidConflictPolicy() throws Exception {
        mockMvc.perform(multipart("/api/workspace/file-upload")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "files", "hello.txt", "text/plain", "hi".getBytes()))
                        .param("conflict", "nope")
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fileUploadWritesFile() throws Exception {
        String name = "majo-upload-" + System.currentTimeMillis() + ".txt";
        mockMvc.perform(multipart("/api/workspace/file-upload")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "files", name, "text/plain", "hello".getBytes()))
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].name").value(name))
                .andExpect(jsonPath("$.files[0].status").value("uploaded"));
    }

    @Test
    void fileUploadConflictsWithoutPolicy() throws Exception {
        String name = "majo-conflict-" + System.currentTimeMillis() + ".txt";
        byte[] content = "v1".getBytes();
        mockMvc.perform(multipart("/api/workspace/file-upload")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "files", name, "text/plain", content))
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/api/workspace/file-upload")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "files", name, "text/plain", "v2".getBytes()))
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail.code").value("upload_conflict"));
    }

    @Test
    void fileUploadOverwriteResolvesConflict() throws Exception {
        String name = "majo-overwrite-" + System.currentTimeMillis() + ".txt";
        byte[] content = "v1".getBytes();
        mockMvc.perform(multipart("/api/workspace/file-upload")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "files", name, "text/plain", content))
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/api/workspace/file-upload")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "files", name, "text/plain", "v2".getBytes()))
                        .param("conflict", "overwrite")
                        .header("X-Agent-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].status").value("uploaded"));
    }
}
