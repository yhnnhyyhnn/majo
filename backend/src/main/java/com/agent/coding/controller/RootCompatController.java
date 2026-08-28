package com.agent.coding.controller;

import com.agent.coding.SettingsService;
import com.agent.coding.WorkspaceContext;
import com.agent.coding.agent.AgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@CrossOrigin(origins = "*")
public class RootCompatController {

    private static final Logger log = LoggerFactory.getLogger(RootCompatController.class);
    private static final String SYS_PROMPT = "你是一个专业的编码助手。回答简洁专业。";
    private static final Path DEFAULT_WORKSPACE = com.agent.coding.skill.SkillStore.WORKING_DIR;

    private final SettingsService settingsService;
    private final Toolkit toolkit;
    private final CronController cronController;
    private final com.agent.coding.security.ToolGuardHook toolGuardHook;
    private final ObjectMapper mapper = new ObjectMapper();

    public RootCompatController(SettingsService settingsService, Toolkit toolkit,
                                CronController cronController,
                                com.agent.coding.security.ToolGuardHook toolGuardHook) {
        this.settingsService = settingsService;
        this.toolkit = toolkit;
        this.cronController = cronController;
        this.toolGuardHook = toolGuardHook;
    }

    // ── Legacy plural cron path (the original migrated /crons/jobs → /cron/jobs).
    // Forward to the current CronController so older clients keep working.

    @GetMapping("/crons/jobs")
    public List<Map<String, Object>> cronsJobs() {
        return cronController.listJobs(null, null);
    }

    @PostMapping("/crons/jobs")
    public ResponseEntity<?> cronsJobCreate(@RequestBody Map<String, Object> body) {
        return cronController.createJob(body, null);
    }

    @DeleteMapping("/crons/jobs/{job_id}")
    public ResponseEntity<?> cronsJobDelete(@PathVariable String job_id) {
        return cronController.deleteJob(job_id, null);
    }

    // ── Voice channel (Twilio phone integration). Majo does not ship the
    // Twilio/ConversationRelay channel, so these webhooks respond with an
    // explicit "channel not available" error ('s
    // build_error_twiml when the voice channel is missing).

    @PostMapping("/voice/incoming")
    public ResponseEntity<String> voiceIncoming() {
        String twiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Response><Say>Voice channel is not available.</Say></Response>";
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_XML).body(twiml);
    }

    @PostMapping("/voice/status-callback")
    public Map<String, String> voiceStatusCallback() {
        return Map.of("status", "ignored");
    }

    @PostMapping(value = "/api/agents/{agentId}/console/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> agentConsoleChat(@PathVariable String agentId, @RequestBody Map<String, Object> body) {
        String prompt = extractPrompt(body);
        if (prompt.isBlank()) return Flux.just(event("error", "prompt is required"));
        String sessionId = Objects.toString(body.getOrDefault("session_id", UUID.randomUUID().toString()), UUID.randomUUID().toString());
        String workspace = Objects.toString(body.getOrDefault("workspace", ""), "");
        WorkspaceContext.set(workspace);

        Path wsPath = DEFAULT_WORKSPACE;
        String agentName = "majo";
        try {
            wsPath = AgentStore.workspaceDirForAgent(agentId);
            var profile = AgentStore.getProfile(agentId);
            if (profile != null && profile.get("name") != null) {
                agentName = profile.get("name").toString();
            }
        } catch (Exception e) {
            log.warn("Failed to resolve agent '{}', using defaults: {}", agentId, e.getMessage());
        }

        HarnessAgent agent = HarnessAgent.builder().name(agentName).sysPrompt(SYS_PROMPT)
            .model(OpenAIChatModel.builder().apiKey(settingsService.getApiKey()).baseUrl(settingsService.getBaseUrl()).modelName(settingsService.getModelName()).build())
            .toolkit(toolkit).workspace(wsPath).hook(toolGuardHook).build();
        var ctx = RuntimeContext.builder().sessionId(sessionId).userId("web-user").build();
        return agent.streamEvents(new UserMessage(prompt), ctx)
            .map(evt -> { try { var n = mapper.createObjectNode(); n.put("type", evt.getClass().getSimpleName()); n.put("timestamp", System.currentTimeMillis()); n.set("data", mapper.valueToTree(evt)); return mapper.writeValueAsString(n); } catch (Exception e) { return event("error", e.getMessage()); } })
            .startWith(event("thinking", "Thinking...")).concatWithValues(event("done", ""))
            .onErrorResume(e -> { log.error("Agent chat error", e); return Flux.just(event("error", e.getMessage()), event("done", "")); })
            .doFinally(sig -> WorkspaceContext.clear());
    }

    @SuppressWarnings("unchecked")
    private String extractPrompt(Map<String, Object> body) {
        Object input = body.get("input");
        if (input instanceof String s && !s.isBlank()) return s;
        if (input instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> msg) {
                Object content = msg.get("content");
                if (content instanceof String s) return s;
                if (content instanceof List<?> parts && !parts.isEmpty() && parts.get(0) instanceof Map<?,?> p)
                    return Objects.toString(p.get("text"), "");
            }
        }
        return "";
    }

    private String event(String type, String content) {
        try { var n = mapper.createObjectNode(); n.put("type", type); n.put("content", content); return mapper.writeValueAsString(n); }
        catch (Exception e) { return "{\"type\":\"error\"}"; }
    }
}
