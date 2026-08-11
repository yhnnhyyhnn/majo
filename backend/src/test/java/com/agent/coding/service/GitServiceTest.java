package com.agent.coding.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the git workspace service (ported from qwenpaw git.py) and the
 * control-command endpoint (qwenpaw CommandRegistry).
 */
@SpringBootTest
@AutoConfigureMockMvc
class GitServiceTest {

    @Autowired
    private GitService gitService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void gitLifecycleWorks() throws Exception {
        Path dir = Files.createTempDirectory("majo-git-");
        try {
            // status auto-initialises the repo.
            var status = gitService.status(dir);
            assertTrue(status.containsKey("branch"));

            // Create a file, stage and commit it.
            Files.writeString(dir.resolve("hello.txt"), "hello git\n");
            gitService.stage(dir, java.util.List.of("hello.txt"));
            var commit = gitService.commit(dir, "add hello");
            assertTrue((Boolean) commit.get("committed"));

            // Log shows the commit.
            var log = gitService.log(dir, 10);
            assertFalse(log.isEmpty());

            // Branches lists at least one.
            var branches = gitService.branches(dir);
            assertFalse(branches.isEmpty());

            // Status reflects the new committed file as clean (untracked none).
            var status2 = gitService.status(dir);
            assertTrue(status2.containsKey("changes"));
        } finally {
            deleteRecursive(dir);
        }
    }

    @Test
    void checkoutCreatesBranch() throws Exception {
        Path dir = Files.createTempDirectory("majo-git-");
        try {
            gitService.status(dir); // init
            var result = gitService.checkout(dir, "feature/x", true);
            assertTrue(result.get("branch").equals("feature/x"));
            var branches = gitService.branches(dir);
            assertTrue(branches.stream().anyMatch(b -> "feature/x".equals(b.get("name"))));
        } finally {
            deleteRecursive(dir);
        }
    }

    @Test
    void commandsCheckMatchesControlCommands() throws Exception {
        mockMvc.perform(post("/api/commands/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"/stop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_control_command").value(true));

        mockMvc.perform(post("/api/commands/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"/stopx\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_control_command").value(false));

        mockMvc.perform(post("/api/commands/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"hello world\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_control_command").value(false));

        mockMvc.perform(post("/api/commands/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"/daemon status\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_control_command").value(true));
    }

    private static void deleteRecursive(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
