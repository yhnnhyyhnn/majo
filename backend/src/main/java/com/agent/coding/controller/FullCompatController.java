package com.agent.coding.controller;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Compatibility layer for qwenpaw endpoints not yet moved into dedicated
 * controllers. Pure stubs remain for parity until implemented.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class FullCompatController {

    // ===== LOCAL MODELS (llama.cpp integration not yet ported) =====
    @GetMapping("/local-models/config")
    public Map<String, String> localModelConfig() { return Map.of("enabled", "false"); }
    @PutMapping("/local-models/config")
    public Map<String, String> localModelConfigUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/local-models/models")
    public List<Map<String, String>> localModels() { return List.of(); }
    @GetMapping("/local-models/models/{model_id}")
    public Map<String, String> localModelDetail(@PathVariable String model_id) { return Map.of("id", model_id); }
    @DeleteMapping("/local-models/models/{model_id}")
    public Map<String, String> localModelDelete(@PathVariable String model_id) { return Map.of("status", "ok"); }
    @GetMapping("/local-models/models/download")
    public Map<String, String> localModelDownload() { return Map.of("status", "ok"); }
    @DeleteMapping("/local-models/models/download")
    public Map<String, String> localModelDownloadCancel() { return Map.of("status", "ok"); }
    @PostMapping("/local-models/models/download")
    public Map<String, String> localModelDownloadStart() { return Map.of("status", "ok"); }
    @GetMapping("/local-models/server")
    public Map<String, String> localModelServer() { return Map.of("status", "stopped"); }
    @DeleteMapping("/local-models/server")
    public Map<String, String> localModelServerDelete() { return Map.of("status", "ok"); }
    @PostMapping("/local-models/server")
    public Map<String, String> localModelServerStart() { return Map.of("status", "ok"); }
    @GetMapping("/local-models/server/download")
    public Map<String, String> localModelServerDownload() { return Map.of("status", "ok"); }
    @DeleteMapping("/local-models/server/download")
    public Map<String, String> localModelServerDownloadCancel() { return Map.of("status", "ok"); }
    @PostMapping("/local-models/server/download")
    public Map<String, String> localModelServerDownloadStart() { return Map.of("status", "ok"); }
    @GetMapping("/local-models/server/update")
    public Map<String, String> localModelServerUpdate() { return Map.of("available", "false"); }
}