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
 * Tests Cron and Pawapps controllers: cron job CRUD/lifecycle and pawapp
 * list/detail/settings.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CronPawappsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void cronJobsListReturnsArray() throws Exception {
        mockMvc.perform(get("/api/cron/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void cronDispatchTargetsReturnsObject() throws Exception {
        mockMvc.perform(get("/api/cron/dispatch-targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.channels").isArray());
    }

    @Test
    void cronJobLifecycle() throws Exception {
        String name = "test-job-" + System.currentTimeMillis();
        String body = "{\"name\": \"" + name + "\", \"enabled\": true, "
                + "\"schedule\": {\"type\": \"cron\", \"cron\": \"0 9 * * *\", \"timezone\": \"UTC\"}, "
                + "\"task_type\": \"text\", \"text\": \"hello\"}";
        String jobId;
        var createResp = mockMvc.perform(post("/api/cron/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.id").isString())
                .andReturn();
        jobId = createResp.getResponse().getContentAsString()
                .replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/cron/jobs/" + jobId))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(post("/api/cron/jobs/" + jobId + "/pause"))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(post("/api/cron/jobs/" + jobId + "/resume"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(delete("/api/cron/jobs/" + jobId))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void cronJobRunAcceptsUnknownId() throws Exception {
        mockMvc.perform(post("/api/cron/jobs/no-such-job/run"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void pawappsListReturnsObject() throws Exception {
        mockMvc.perform(get("/api/pawapps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apps").isArray())
                .andExpect(jsonPath("$.total").isNumber());
    }

    @Test
    void pawappUnknownReturns404() throws Exception {
        mockMvc.perform(get("/api/pawapps/no-such-app"))
                .andExpect(status().isNotFound());
    }
}
