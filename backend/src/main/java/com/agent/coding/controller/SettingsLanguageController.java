package com.agent.coding.controller;

import com.agent.coding.skill.SkillStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Global UI settings (language, upload limit), ported from
 * qwenpaw app/routers/settings.py. Persisted in {@code WORKING_DIR/settings.json},
 * independent of per-agent configuration. All endpoints are public.
 */
@RestController
@RequestMapping("/api/settings")
@CrossOrigin(origins = "*")
public class SettingsLanguageController {

    private static final Set<String> VALID_LANGUAGES = Set.of("en", "zh", "ja", "ru", "pt-BR", "id");
    private static final long UPLOAD_MAX_SIZE_MB = 500L;

    private Path settingsFile() {
        return SkillStore.WORKING_DIR.resolve("settings.json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        Path file = settingsFile();
        if (Files.isRegularFile(file)) {
            Map<String, Object> data = SkillStore.readJson(file, Map.of());
            return data.isEmpty() ? new LinkedHashMap<>() : data;
        }
        return new LinkedHashMap<>();
    }

    private void save(Map<String, Object> data) {
        SkillStore.writeJsonAtomic(settingsFile(), data);
    }

    @GetMapping("/language")
    public Map<String, Object> getLanguage() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("language", load().getOrDefault("language", "en"));
        return result;
    }

    @PutMapping("/language")
    public ResponseEntity<?> updateLanguage(@RequestBody Map<String, Object> body) {
        String language = String.valueOf(body.getOrDefault("language", "")).trim();
        if (!VALID_LANGUAGES.contains(language)) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "Invalid language, must be one of " + VALID_LANGUAGES.stream().sorted().toList()
            ));
        }
        Map<String, Object> data = load();
        data.put("language", language);
        save(data);
        return ResponseEntity.ok(Map.of("language", language));
    }

    @GetMapping("/upload-limit")
    public Map<String, Object> getUploadLimit() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("upload_max_size_mb", UPLOAD_MAX_SIZE_MB);
        return result;
    }
}
