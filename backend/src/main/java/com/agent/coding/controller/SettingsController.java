package com.agent.coding.controller;

import com.agent.coding.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    public Map<String, String> getSettings() {
        return Map.of(
            "apiKey", settingsService.getApiKey(),
            "baseUrl", settingsService.getBaseUrl(),
            "modelName", settingsService.getModelName(),
            "workspace", settingsService.getWorkspace()
        );
    }

    @PostMapping("/settings")
    public Map<String, String> saveSettings(@RequestBody Map<String, String> body) {
        log.info("Saving settings — received: modelName={}, baseUrl={}, apiKey={}...",
            body.getOrDefault("modelName", "(not set)"),
            body.getOrDefault("baseUrl", "(not set)"),
            body.containsKey("apiKey") ? body.get("apiKey").substring(0, Math.min(8, body.get("apiKey").length())) : "(not set)");
        if (body.containsKey("apiKey")) {
            settingsService.setApiKey(body.get("apiKey"));
        }
        if (body.containsKey("baseUrl")) {
            settingsService.setBaseUrl(body.get("baseUrl"));
        }
        if (body.containsKey("modelName")) {
            settingsService.setModelName(body.get("modelName"));
        }
        if (body.containsKey("workspace")) {
            settingsService.setWorkspace(body.get("workspace"));
        }
        Map<String, String> result = getSettings();
        log.info("Settings saved — current: modelName={}, baseUrl={}", 
            result.get("modelName"), result.get("baseUrl"));
        return result;
    }

    @GetMapping("/settings/offload-policy")
    public Map<String, String> getOffloadPolicy() {
        return Map.of("default_action",
                com.agent.coding.agent.AgentStore.getOffloadPolicy());
    }

    @PutMapping("/settings/offload-policy")
    public Map<String, String> setOffloadPolicy(@RequestBody Map<String, String> body) {
        String action = body.getOrDefault("default_action", "keep_foreground");
        com.agent.coding.agent.AgentStore.setOffloadPolicy(action);
        return Map.of("default_action", action);
    }
}
