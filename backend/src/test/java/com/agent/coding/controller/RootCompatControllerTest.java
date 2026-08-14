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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the root-level compatibility endpoints (legacy plural cron path and
 * voice webhooks) in RootCompatController.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RootCompatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pluralCronsJobsForwardsToList() throws Exception {
        mockMvc.perform(get("/crons/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void pluralCronsJobCreateForwards() throws Exception {
        mockMvc.perform(post("/crons/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "legacy-test", "schedule": {"type": "cron", "cron": "0 9 * * *"},
                                 "task_type": "agent", "request": {"input": "hi"},
                                 "dispatch": {"channel": "console", "mode": "stream"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("legacy-test"));
    }

    @Test
    void pluralCronsJobDeleteUnknownReturns404() throws Exception {
        mockMvc.perform(delete("/crons/jobs/nonexistent-id"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void voiceIncomingReturnsTwimlError() throws Exception {
        mockMvc.perform(post("/voice/incoming"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Voice channel is not available")));
    }

    @Test
    void voiceStatusCallbackIgnored() throws Exception {
        mockMvc.perform(post("/voice/status-callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));
    }
}
