package com.agent.coding.controller;

import com.agent.coding.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-selectable accent colors (QwenPaw #7741 counterpart). Persisted in
 * the settings table; DELETE resets to the built-in orange.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ThemeController {

    private final SettingsService settingsService;

    public ThemeController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/config/theme")
    public Map<String, Object> get() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accent", settingsService.getThemeAccent() == null
                ? "" : settingsService.getThemeAccent());
        result.put("accent_dark", settingsService.getThemeAccentDark() == null
                ? "" : settingsService.getThemeAccentDark());
        return result;
    }

    @PutMapping("/config/theme")
    public ResponseEntity<?> update(@RequestBody Map<String, Object> body) {
        try {
            settingsService.setThemeAccents(
                    body.get("accent") == null ? "" : String.valueOf(body.get("accent")),
                    body.get("accent_dark") == null ? "" : String.valueOf(body.get("accent_dark")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
        }
        return ResponseEntity.ok(get());
    }

    @DeleteMapping("/config/theme")
    public Map<String, Object> reset() {
        settingsService.setThemeAccents("", "");
        return get();
    }
}
