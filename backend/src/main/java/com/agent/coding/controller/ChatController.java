package com.agent.coding.controller;

import com.agent.coding.SettingsService;
import com.agent.coding.WorkspaceContext;
import com.agent.coding.dto.StatusResponse;
import com.agent.coding.entity.ModelConfigEntity;
import com.agent.coding.repository.ModelConfigRepository;
import com.agent.coding.service.ModelRoutingService;
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
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final String SYS_PROMPT = "你是一个专业的编码助手。工具包括: read_file/write_file/edit_file(读写编辑), search_code/find_symbol/list_directory(搜索), execute_command(执行命令), git_status/git_diff/git_branch/git_commit/git_add/git_log(Git操作)。回答简洁专业。";
    private static final Path DEFAULT_WORKSPACE = com.agent.coding.skill.SkillStore.WORKING_DIR;

    private final SettingsService settingsService;
    private final ModelConfigRepository modelConfigRepo;
    private final ModelRoutingService modelRouting;
    private final Toolkit toolkit;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatController(SettingsService settingsService, ModelConfigRepository modelConfigRepo,
                          ModelRoutingService modelRouting, Toolkit toolkit) {
        this.settingsService = settingsService;
        this.modelConfigRepo = modelConfigRepo;
        this.modelRouting = modelRouting;
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
        String modelId = body.getOrDefault("modelId", "");

        WorkspaceContext.set(workspace);
        HarnessAgent agent = resolveAgent(workspace, modelId);

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
            .onErrorResume(e -> {
                log.error("Chat stream error", e);
                return Flux.just(event("error", e.getMessage()), event("done", ""));
            })
            .doFinally(sig -> WorkspaceContext.clear());
    }

    private HarnessAgent resolveAgent(String workspace, String modelId) {
        Path wsPath;
        if (workspace.isBlank()) {
            wsPath = DEFAULT_WORKSPACE;
        } else {
            wsPath = Paths.get(workspace).toAbsolutePath().normalize();
            if (!Files.isDirectory(wsPath)) {
                log.warn("Workspace not found or not a directory: {}, using default", workspace);
                wsPath = DEFAULT_WORKSPACE;
            }
        }
        log.info("Using workspace: {}", wsPath);
        return HarnessAgent.builder()
            .name("majo")
            .sysPrompt(SYS_PROMPT)
            .model(resolveModel(modelId))
            .toolkit(toolkit)
            .workspace(wsPath)
            .build();
    }

    private OpenAIChatModel resolveModel(String modelId) {
        // Try to resolve from model config by ID
        if (!modelId.isBlank()) {
            try {
                Long id = Long.parseLong(modelId);
                Optional<ModelConfigEntity> opt = modelConfigRepo.findById(id);
                if (opt.isPresent()) {
                    ModelConfigEntity cfg = opt.get();
                    log.info("Using model config #{}: name={}, modelName={}, baseUrl={}",
                        cfg.getId(), cfg.getName(), cfg.getModelName(), cfg.getBaseUrl());
                    return OpenAIChatModel.builder()
                        .apiKey(cfg.getApiKey())
                        .baseUrl(cfg.getBaseUrl())
                        .modelName(cfg.getModelName())
                        .build();
                }
                log.warn("Model config #{} not found, falling back to default settings", id);
            } catch (NumberFormatException e) {
                log.warn("Invalid modelId: {}, falling back to default settings", modelId);
            }
        }

        // Fallback: resolve effective model from the qwenpaw-aligned routing
        var slot = modelRouting.resolveEffectiveModel(null);
        if (slot.hasBoth()) {
            log.info("Resolved effective model: {}/{}", slot.providerId(), slot.modelId());
            return modelRouting.buildOpenAIChatModel(slot.providerId(), slot.modelId());
        }
        // Ultimate fallback
        String apiKey = settingsService.getApiKey();
        String baseUrl = settingsService.getBaseUrl();
        String modelName = settingsService.getModelName();
        log.info("Using legacy settings — baseUrl: {}, modelName: {}, apiKey: {}...",
            baseUrl, modelName,
            apiKey.length() > 8 ? apiKey.substring(0, 8) : apiKey);
        return OpenAIChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(modelName)
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
    public StatusResponse health() {
        return StatusResponse.ok();
    }
}
