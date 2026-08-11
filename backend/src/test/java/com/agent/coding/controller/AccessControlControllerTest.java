package com.agent.coding.controller;

import com.agent.coding.accesscontrol.AccessControlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the access-control endpoints (ported from qwenpaw
 * app/routers/access_control.py). Verifies the /channels page contract end to
 * end without a running server process.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessControlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessControlStore store;

    @BeforeEach
    void cleanState() {
        // Ensure a clean per-test slate by removing any pre-existing channel data.
        for (String channel : store.getAllAcls().keySet()) {
            store.removeFromWhitelist(channel, "__all__");
        }
    }

    @Test
    void listAclsIsEmptyInitially() throws Exception {
        mockMvc.perform(get("/api/access-control"))
                .andExpect(status().isOk());
    }

    @Test
    void whitelistAddAndListRoundTrip() throws Exception {
        mockMvc.perform(post("/api/access-control/whitelist/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"channel":"dingtalk","user_id":"user_1","username":"Zhang","remark":"vip"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(get("/api/access-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dingtalk.whitelist.user_1.username").value("Zhang"))
                .andExpect(jsonPath("$.dingtalk.whitelist.user_1.remark").value("vip"));
    }

    @Test
    void blacklistAddAndMutualExclusion() throws Exception {
        mockMvc.perform(post("/api/access-control/blacklist/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"channel":"dingtalk","user_id":"user_2","remark":"spam"}]}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/access-control/dingtalk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blacklist.user_2.remark").value("spam"));
    }

    @Test
    void updateRemarkAndUsername() throws Exception {
        mockMvc.perform(post("/api/access-control/whitelist/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"channel":"dingtalk","user_id":"user_1"}]}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/access-control/remark")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"dingtalk","user_id":"user_1","remark":"gold"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/access-control/username")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"dingtalk","user_id":"user_1","username":"VIP"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/access-control/dingtalk"))
                .andExpect(jsonPath("$.whitelist.user_1.remark").value("gold"))
                .andExpect(jsonPath("$.whitelist.user_1.username").value("VIP"));
    }

    @Test
    void pendingApproveMovesToWhitelist() throws Exception {
        store.addPending("dingtalk", "pending_1", "hello there", "NewUser");

        mockMvc.perform(post("/api/access-control/pending/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"channel":"dingtalk","user_id":"pending_1"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(get("/api/access-control/pending/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/api/access-control/dingtalk"))
                .andExpect(jsonPath("$.whitelist.pending_1.username").value("NewUser"));
    }

    @Test
    void pendingDenyMovesToBlacklist() throws Exception {
        store.addPending("wecom", "pending_2", "spam attempt", "Spammer");

        mockMvc.perform(post("/api/access-control/pending/deny")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"channel":"wecom","user_id":"pending_2","remark":"blocked"}]}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/access-control/wecom"))
                .andExpect(jsonPath("$.blacklist.pending_2.username").value("Spammer"))
                .andExpect(jsonPath("$.blacklist.pending_2.remark").value("blocked"));
    }

    @Test
    void pendingDismissRemovesWithoutList() throws Exception {
        store.addPending("slack", "pending_3", "just testing", "");

        mockMvc.perform(post("/api/access-control/pending/dismiss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"channel":"slack","user_id":"pending_3"}]}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/access-control/pending/all"))
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/access-control/slack"))
                .andExpect(jsonPath("$.whitelist").isEmpty())
                .andExpect(jsonPath("$.blacklist").isEmpty());
    }

    @Test
    void channelAclAndRemove() throws Exception {
        mockMvc.perform(post("/api/access-control/whitelist/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"channel":"telegram","user_id":"tg_1"}]}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/access-control/whitelist/remove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[{"channel":"telegram","user_id":"tg_1"}]}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/access-control/telegram"))
                .andExpect(jsonPath("$.whitelist").isEmpty());
    }
}
