package com.agent.coding.controller;

import com.agent.coding.SettingsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SettingsController {

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
        return getSettings();
    }
}
