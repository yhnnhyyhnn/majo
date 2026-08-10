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

    // ===== ACCESS CONTROL (depends on channel system, not yet ported) =====
    @GetMapping("/access-control")
    public List<Map<String, String>> acList() { return List.of(); }
    @GetMapping("/access-control/{channel}")
    public Map<String, String> acChannel(@PathVariable String channel) { return Map.of("mode", "allow_all"); }
    @PostMapping("/access-control/blacklist/add")
    public Map<String, String> acBlacklistAdd() { return Map.of("status", "ok"); }
    @PostMapping("/access-control/blacklist/remove")
    public Map<String, String> acBlacklistRemove() { return Map.of("status", "ok"); }
    @GetMapping("/access-control/pending/all")
    public List<Map<String, String>> acPending() { return List.of(); }
    @PostMapping("/access-control/pending/approve")
    public Map<String, String> acApprove() { return Map.of("status", "ok"); }
    @PostMapping("/access-control/pending/deny")
    public Map<String, String> acDeny() { return Map.of("status", "ok"); }
    @PostMapping("/access-control/pending/dismiss")
    public Map<String, String> acDismiss() { return Map.of("status", "ok"); }
    @PostMapping("/access-control/pending/remark")
    public Map<String, String> acRemark() { return Map.of("status", "ok"); }
    @PostMapping("/access-control/remark")
    public Map<String, String> acSetRemark() { return Map.of("status", "ok"); }
    @PostMapping("/access-control/username")
    public Map<String, String> acUsername() { return Map.of("status", "ok"); }
    @PostMapping("/access-control/whitelist/add")
    public Map<String, String> acWhitelistAdd() { return Map.of("status", "ok"); }
    @PostMapping("/access-control/whitelist/remove")
    public Map<String, String> acWhitelistRemove() { return Map.of("status", "ok"); }

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