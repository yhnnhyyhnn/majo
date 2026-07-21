package com.agent.coding.controller;

import com.agent.coding.WorkspaceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final HarnessAgent defaultAgent;
    private final OpenAIChatModel model;
    private final Toolkit toolkit;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatController(HarnessAgent defaultAgent, OpenAIChatModel model, Toolkit toolkit) {
        this.defaultAgent = defaultAgent;
        this.model = model;
        this.toolkit = toolkit;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody Map<String, String> body) {
        String prompt = body.getOrDefault("prompt", "");
        if (prompt.isBlank()) {
            return Flux.just(event("error", "prompt is required"));
        }

        String sessionId = body.getOrDefault("sessionId", UUID.randomUUID().toString());
        String workspace = body.getOrDefault("workspace", "");

        WorkspaceContext.set(workspace);
        HarnessAgent agent = resolveAgent(workspace);

        var ctx = RuntimeContext.builder()
            .sessionId(sessionId)
            .userId("web-user")
            .build();

        return agent.streamEvents(new UserMessage(prompt), ctx)
            .map(event -> {
                try {
                    var node = mapper.createObjectNode();
                    String type = event.getClass().getSimpleName();
                    node.put("type", type);
                    node.put("timestamp", System.currentTimeMillis());

                    var data = mapper.valueToTree(event);
                    node.set("data", data);

                    return mapper.writeValueAsString(node);
                } catch (Exception e) {
                    return event("error", e.getMessage());
                }
            })
            .startWith(event("thinking", "Thinking..."))
            .concatWithValues(event("done", ""))
            .doFinally(sig -> WorkspaceContext.clear());
    }

    private HarnessAgent resolveAgent(String workspace) {
        if (workspace.isBlank()) {
            return defaultAgent;
        }
        Path wsPath = Paths.get(workspace).toAbsolutePath().normalize();
        if (!Files.isDirectory(wsPath)) {
            log.warn("Workspace not found or not a directory: {}, using default", workspace);
            return defaultAgent;
        }
        log.info("Using workspace: {}", wsPath);
        return HarnessAgent.builder()
            .name("majo")
            .sysPrompt("你是一个专业的编码助手。工具包括: read_file/write_file/edit_file(读写编辑), search_code/list_directory(搜索), execute_command(执行命令), git_status/git_diff/git_branch/git_commit/git_add/git_log(Git操作)。回答简洁专业。")
            .model(model)
            .toolkit(toolkit)
            .workspace(wsPath)
            .build();
    }

    private String event(String type, String content) {
        try {
            var node = mapper.createObjectNode();
            node.put("type", type);
            node.put("content", content);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"type\":\"error\"}";
        }
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
