package com.agent.coding.controller;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class PawappsController {

    @GetMapping("/pawapps")
    public Map<String, Object> list() {
        return Map.of("apps", List.of(), "total", 0);
    }

    @GetMapping("/pawapps/{appId}")
    public Map<String, Object> get(@PathVariable String appId) {
        return Map.of("id", appId, "name", "", "version", "0.0.0", "description", "", "status", "not_found");
    }

    @GetMapping("/pawapps/{appId}/settings")
    public Map<String, Object> settings(@PathVariable String appId) {
        return Map.of("app_id", appId, "settings", List.of());
    }

    @GetMapping("/pawapps/{appId}/static/{filePath}")
    public Map<String, String> staticFile(@PathVariable String appId, @PathVariable String filePath) {
        return Map.of("content", "");
    }
}
