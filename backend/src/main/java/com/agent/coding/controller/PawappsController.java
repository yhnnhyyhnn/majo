package com.agent.coding.controller;

import com.agent.coding.skill.SkillService;
import com.agent.coding.skill.SkillStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PawApp (app-type plugin) management, ported from qwenpaw app/routers/pawapps.py.
 * Apps install into the shared plugins directory; their manifest carries a
 * {@code pawapp} meta section marking them as apps.
 */
@RestController
@RequestMapping("/api/pawapps")
@CrossOrigin(origins = "*")
public class PawappsController {

    private static final Path PLUGINS_DIR = SkillStore.WORKING_DIR.resolve("plugins");

    static {
        try {
            Files.createDirectories(PLUGINS_DIR);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> appInfo(File dir) {
        Path manifestPath = dir.toPath().resolve("plugin.json");
        Map<String, Object> manifest = Files.isRegularFile(manifestPath)
                ? SkillStore.readJson(manifestPath, Map.of()) : Map.of();
        String fallbackId = dir.getName();
        String id = SkillService.str(manifest.getOrDefault("id", fallbackId));
        Map<String, Object> meta = SkillService.asMap(manifest.get("meta"));
        Map<String, Object> pawappMeta = SkillService.asMap(meta.get("pawapp"));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", id);
        info.put("name", SkillService.str(manifest.getOrDefault("name", fallbackId)));
        info.put("version", SkillService.str(manifest.getOrDefault("version", "0.0.0")));
        info.put("description", SkillService.str(manifest.getOrDefault("description", "")));
        info.put("author", SkillService.str(manifest.getOrDefault("author", "")));
        info.put("category", SkillService.str(pawappMeta.get("category")));
        info.put("icon", SkillService.str(pawappMeta.get("icon")));
        info.put("entry_page", SkillService.str(pawappMeta.get("entry_page")));
        info.put("launch_scope", SkillService.str(pawappMeta.get("launch_scope")));
        info.put("status", "installed");
        info.put("home_page", null);
        info.put("dir", dir.getAbsolutePath());
        Object settings = pawappMeta.getOrDefault("settings", List.of());
        info.put("settings", settings instanceof List<?> l ? l : List.of());
        info.put("permissions", pawappMeta.getOrDefault("permissions", Map.of()));
        info.put("backends", pawappMeta.getOrDefault("backends", Map.of()));
        return info;
    }

    private List<File> appDirs() {
        File[] dirs = PLUGINS_DIR.toFile().listFiles(File::isDirectory);
        List<File> result = new ArrayList<>();
        if (dirs == null) {
            return result;
        }
        for (File dir : dirs) {
            Path manifestPath = dir.toPath().resolve("plugin.json");
            if (!Files.isRegularFile(manifestPath)) {
                continue;
            }
            Map<String, Object> manifest = SkillStore.readJson(manifestPath, Map.of());
            Map<String, Object> meta = SkillService.asMap(manifest.get("meta"));
            Map<String, Object> pawappMeta = SkillService.asMap(meta.get("pawapp"));
            if (!pawappMeta.isEmpty() || manifest.containsKey("entry_page")
                    || SkillService.str(meta.get("pawapp")).equals("true")) {
                result.add(dir);
            }
        }
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> apps = new ArrayList<>();
        for (File dir : appDirs()) {
            apps.add(appInfo(dir));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apps", apps);
        result.put("total", apps.size());
        return result;
    }

    @GetMapping("/{app_id}")
    public Map<String, Object> get(@PathVariable String app_id) {
        for (File dir : appDirs()) {
            Map<String, Object> info = appInfo(dir);
            if (app_id.equals(SkillService.str(info.get("id"))) || app_id.equals(dir.getName())) {
                return info;
            }
        }
        Map<String, Object> notFound = new LinkedHashMap<>();
        notFound.put("id", app_id);
        notFound.put("name", "");
        notFound.put("version", "0.0.0");
        notFound.put("description", "");
        notFound.put("status", "not_found");
        notFound.put("settings", List.of());
        return notFound;
    }

    @DeleteMapping("/{app_id}")
    public ResponseEntity<?> uninstall(@PathVariable String app_id) {
        if (app_id.isBlank() || app_id.contains("/") || app_id.contains("\\")
                || app_id.equals(".") || app_id.equals("..")) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Invalid app id"));
        }
        Path target = PLUGINS_DIR.resolve(app_id).normalize();
        if (!target.startsWith(PLUGINS_DIR.normalize())) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Invalid app path"));
        }
        if (!Files.isDirectory(target)) {
            return ResponseEntity.status(404).body(Map.of("detail", "PawApp not found: " + app_id));
        }
        try (var walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("detail", "Uninstall failed: " + e.getMessage()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", app_id);
        result.put("message", "PawApp '" + app_id + "' uninstalled.");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{app_id}/settings")
    public Map<String, Object> settings(@PathVariable String app_id) {
        Map<String, Object> info = get(app_id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("app_id", app_id);
        result.put("settings", info.getOrDefault("settings", List.of()));
        return result;
    }

    @GetMapping("/{app_id}/static/{file_path:.*}")
    public ResponseEntity<?> staticFile(@PathVariable String app_id, @PathVariable String file_path) {
        Path target = PLUGINS_DIR.resolve(app_id).resolve(file_path).normalize();
        if (!target.startsWith(PLUGINS_DIR.normalize()) || !Files.isRegularFile(target)) {
            return ResponseEntity.status(404).body(Map.of("detail", "File not found"));
        }
        try {
            String content = Files.readString(target);
            return ResponseEntity.ok(Map.of("content", content));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("detail", e.getMessage()));
        }
    }
}
