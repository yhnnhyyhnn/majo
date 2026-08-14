package com.agent.coding.loop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests LoopSessionManager integration with /loops/status: an active loop
 * session reports running + mode, and ends as idle.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoopSessionManagerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoopSessionManager loopSessionManager;

    @Test
    void statusWithoutSessionIsIdle() throws Exception {
        mockMvc.perform(get("/api/loops/status").param("session_id", "no-such-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("idle"));
    }

    @Test
    void activeSessionReportsRunningWithMode() {
        StopHandler handler = new StopHandler();
        handler.register(new IterationGate(20));
        LoopSessionManager.LoopSession session = loopSessionManager.start(
                "sess-loop-1", "goal", "goal", "Fix the bug", handler);
        try {
            java.util.Map<String, Object> status = loopSessionManager.status("sess-loop-1");
            org.junit.jupiter.api.Assertions.assertEquals("running", status.get("state"));
            org.junit.jupiter.api.Assertions.assertEquals("goal",
                    ((java.util.Map<?, ?>) status.get("mode")).get("id"));
        } finally {
            loopSessionManager.end("sess-loop-1");
        }
    }

    @Test
    void endedSessionIsIdle() {
        StopHandler handler = new StopHandler();
        handler.register(new IterationGate(20));
        loopSessionManager.start("sess-loop-2", "mission", "mission", "Ship it", handler);
        loopSessionManager.end("sess-loop-2");
        org.junit.jupiter.api.Assertions.assertEquals("idle",
                loopSessionManager.status("sess-loop-2").get("state"));
    }
}
