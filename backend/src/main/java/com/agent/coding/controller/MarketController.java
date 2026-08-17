package com.agent.coding.controller;

import com.agent.coding.skill.SkillStore;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill market endpoints,.
 *
 * <p>Majo exposes the local skill pool (built-in + workspace skills) as a
 * single built-in provider; the frontend Market panel can therefore list and
 * search available skills without any remote dependency.
 */
@RestController
@RequestMapping("/api/market")
@CrossOrigin(origins = "*")
public class MarketController {

    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("key", "majo-pool");
        provider.put("label", "Majo Skill Pool");
        provider.put("available", true);
        provider.put("reason", null);
        provider.put("supports_browse", true);
        result.add(provider);
        return result;
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories(@RequestParam(defaultValue = "en") String lang) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(category("general", "General"));
        result.add(category("coding", "Coding"));
        result.add(category("browser", "Browser"));
        result.add(category("communication", "Communication"));
        result.add(category("productivity", "Productivity"));
        return result;
    }

    private static Map<String, Object> category(String id, String label) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("label", label);
        return c;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        String query = String.valueOf(body.getOrDefault("query", "")).trim().toLowerCase();
        int limit = body.get("limit") == null ? 10 : ((Number) body.get("limit")).intValue();

        List<Map<String, Object>> results = new ArrayList<>();
        List<java.nio.file.Path> poolDirs = SkillStore.getSkillPoolDirs();
        for (java.nio.file.Path skillDir : poolDirs) {
            String name = skillDir.getFileName().toString();
            if (name.endsWith("-en") || name.endsWith("-zh")) {
                name = name.substring(0, name.length() - 3);
            }
            if (!query.isEmpty() && !name.toLowerCase().contains(query)) {
                continue;
            }
            Map<String, Object> meta = SkillStore.readFrontmatterSafe(skillDir, name);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", "majo-pool");
            result.put("slug", name);
            result.put("name", meta.getOrDefault("name", name));
            result.put("description", meta.getOrDefault("description", null));
            result.put("source_url", "");
            result.put("version", meta.getOrDefault("version", null));
            result.put("author", meta.getOrDefault("author", null));
            result.put("icon_url", null);
            result.put("stats", null);
            results.add(result);
            if (results.size() >= limit) {
                break;
            }
        }

        Map<String, Object> byProvider = new LinkedHashMap<>();
        Map<String, Object> pageInfo = new LinkedHashMap<>();
        pageInfo.put("has_more", false);
        pageInfo.put("total", results.size());
        byProvider.put("majo-pool", pageInfo);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("errors", List.of());
        response.put("by_provider", byProvider);
        return response;
    }
}
