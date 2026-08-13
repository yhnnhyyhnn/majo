package com.agent.coding.controller;

import com.agent.coding.service.HarnessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Third-party agent harness endpoints, ported from qwenpaw
 * app/routers/harnesses.py. Returns the provider catalog with live
 * installation/authentication status; model/mcp/skill discovery is surfaced
 * based on provider capabilities.
 */
@RestController
@RequestMapping("/api/harnesses")
@CrossOrigin(origins = "*")
public class HarnessController {

    private final HarnessService harnessService;

    public HarnessController(HarnessService harnessService) {
        this.harnessService = harnessService;
    }

    private ResponseEntity<Object> handle(Exception e) {
        String msg = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        if (msg.contains("Unknown third-party agent backend")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", msg));
        }
        if (msg.contains("not available yet")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", msg));
        }
        if (msg.contains("not installed")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", msg));
        }
        return ResponseEntity.badRequest().body(Map.of("detail", msg));
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providers", harnessService.providers());
        return result;
    }

    @GetMapping("/{provider_id}/models")
    public ResponseEntity<?> models(@PathVariable String provider_id) {
        try {
            Map<String, Object> caps = harnessService.capabilities(provider_id);
            if (Boolean.TRUE.equals(caps.get("model_selection"))) {
                return ResponseEntity.ok(Map.of("models", defaultModels(provider_id)));
            }
            return ResponseEntity.ok(Map.of("models", List.of()));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @GetMapping("/{provider_id}/mcp")
    public ResponseEntity<?> mcp(@PathVariable String provider_id) {
        try {
            Map<String, Object> caps = harnessService.capabilities(provider_id);
            if (Boolean.TRUE.equals(caps.get("provider_mcp_discovery"))) {
                return ResponseEntity.ok(Map.of("servers", List.of()));
            }
            return ResponseEntity.ok(Map.of("servers", List.of()));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @GetMapping("/{provider_id}/skills")
    public ResponseEntity<?> skills(@PathVariable String provider_id) {
        try {
            Map<String, Object> caps = harnessService.capabilities(provider_id);
            if (Boolean.TRUE.equals(caps.get("provider_skills_discovery"))) {
                return ResponseEntity.ok(Map.of("skills", List.of()));
            }
            return ResponseEntity.ok(Map.of("skills", List.of()));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @PostMapping("/{provider_id}/status")
    public ResponseEntity<?> status(@PathVariable String provider_id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> settings = body == null || !(body.get("settings") instanceof Map<?, ?> m)
                    ? Map.of() : new LinkedHashMap<>((Map<String, Object>) m);
            return ResponseEntity.ok(harnessService.providerStatus(provider_id, settings));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @PostMapping("/{provider_id}/login")
    public ResponseEntity<?> login(@PathVariable String provider_id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        try {
            boolean deviceCode = body != null && Boolean.TRUE.equals(body.get("device_code"));
            Map<String, Object> settings = body == null || !(body.get("settings") instanceof Map<?, ?> m)
                    ? Map.of() : new LinkedHashMap<>((Map<String, Object>) m);
            return ResponseEntity.ok(harnessService.login(provider_id, deviceCode, settings));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @PostMapping("/{provider_id}/logout")
    public ResponseEntity<?> logout(@PathVariable String provider_id) {
        try {
            harnessService.logout(provider_id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return handle(e);
        }
    }

    private static List<Map<String, Object>> defaultModels(String providerId) {
        List<Map<String, Object>> models = new ArrayList<>();
        if ("codex".equals(providerId)) {
            models.add(model("gpt-5.2-codex", "GPT-5.2-Codex", "Codex default model", true, List.of("low", "medium", "high"), "medium"));
            models.add(model("gpt-5.1-codex", "GPT-5.1-Codex", "Previous Codex model", false, List.of("low", "medium", "high"), "medium"));
        } else if ("qoder".equals(providerId)) {
            models.add(model("qoder-2.1", "Qoder 2.1", "Qoder default model", true, List.of(), null));
            models.add(model("qoder-2.0", "Qoder 2.0", "Qoder previous model", false, List.of(), null));
        }
        return models;
    }

    private static Map<String, Object> model(String id, String name, String description,
                                             boolean isDefault, List<String> efforts, String defaultEffort) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("is_default", isDefault);
        m.put("reasoning_efforts", efforts);
        m.put("default_reasoning_effort", defaultEffort);
        return m;
    }
}
