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

    // ===== ACCESS CONTROL =====
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


    // ===== AGENT TOOLS (implemented in ToolsController) =====

    // ===== APPROVAL =====
    @PostMapping("/approval/approve")
    public Map<String, String> approvalApprove() { return Map.of("status", "ok"); }
    @PostMapping("/approval/deny")
    public Map<String, String> approvalDeny() { return Map.of("status", "ok"); }

    // ===== AUTH =====
    @PostMapping("/auth/login")
    public Map<String, String> authLogin() { return Map.of("token", "stub-token"); }
    @PostMapping("/auth/register")
    public Map<String, String> authRegister() { return Map.of("token", "stub-token"); }
    @PostMapping("/auth/revoke-all-tokens")
    public Map<String, String> authRevokeAll() { return Map.of("status", "ok"); }
    @PostMapping("/auth/revoke-token")
    public Map<String, String> authRevoke() { return Map.of("status", "ok"); }
    @PostMapping("/auth/update-profile")
    public Map<String, String> authUpdateProfile() { return Map.of("status", "ok"); }
    @GetMapping("/auth/verify")
    public Map<String, Object> authVerify() { return Map.of("valid", true); }

    // ===== BACKUPS =====
    @GetMapping("/backups/{backup_id}")
    public Map<String, String> backupDetail(@PathVariable String backup_id) { return Map.of("id", backup_id); }
    @GetMapping("/backups/{backup_id}/export")
    public Map<String, String> backupExport(@PathVariable String backup_id) { return Map.of("url", ""); }
    @PostMapping("/backups/{backup_id}/restore")
    public Map<String, String> backupRestore(@PathVariable String backup_id) { return Map.of("status", "ok"); }
    @PostMapping("/backups/delete")
    public Map<String, String> backupDelete() { return Map.of("status", "ok"); }
    @PostMapping("/backups/import")
    public Map<String, String> backupImport() { return Map.of("status", "ok"); }
    @PostMapping("/backups/stream")
    public Map<String, String> backupStream() { return Map.of("status", "ok"); }

    // ===== CHATS =====
    // ===== CONFIG (global) =====
    @GetMapping("/config/agents/llm-routing")
    public Map<String, String> configLlmRouting() { return Map.of("mode", "default"); }
    @PutMapping("/config/agents/llm-routing")
    public Map<String, String> configLlmRoutingUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/config/security/allow-no-auth-hosts")
    public Map<String, Object> configSecurityAuthHosts() { return Map.of("enabled", false, "hosts", List.of()); }
    @PutMapping("/config/security/allow-no-auth-hosts")
    public Map<String, String> configSecurityAuthHostsUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/config/security/file-guard")
    public Map<String, Object> configSecurityFileGuard() { return Map.of("enabled", false); }
    @PutMapping("/config/security/file-guard")
    public Map<String, String> configSecurityFileGuardUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/config/security/sandbox")
    public Map<String, Object> configSecuritySandbox() { return Map.of("enabled", false); }
    @PutMapping("/config/security/sandbox")
    public Map<String, String> configSecuritySandboxUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/config/security/skill-scanner")
    public Map<String, Object> configSkillScanner() { return Map.of("enabled", false); }
    @PutMapping("/config/security/skill-scanner")
    public Map<String, String> configSkillScannerUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/config/security/skill-scanner/blocked-history")
    public List<Map<String, String>> configSkillBlockedHistory() { return List.of(); }
    @DeleteMapping("/config/security/skill-scanner/blocked-history")
    public Map<String, String> configSkillBlockedHistoryClear() { return Map.of("status", "ok"); }
    @DeleteMapping("/config/security/skill-scanner/blocked-history/{index}")
    public Map<String, String> configSkillBlockedDelete(@PathVariable String index) { return Map.of("status", "ok"); }
    @PostMapping("/config/security/skill-scanner/whitelist")
    public Map<String, String> configSkillWhitelistAdd() { return Map.of("status", "ok"); }
    @DeleteMapping("/config/security/skill-scanner/whitelist/{skill_name}")
    public Map<String, String> configSkillWhitelistRemove(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @GetMapping("/config/security/tool-guard")
    public Map<String, Object> configToolGuard() { return Map.of("enabled", false); }
    @PutMapping("/config/security/tool-guard")
    public Map<String, String> configToolGuardUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/config/security/tool-guard/builtin-rules")
    public List<Map<String, String>> configToolGuardRules() { return List.of(); }

    // ==== CONSOLE ====
    // NOTE: /console/inbox/events, /console/inbox/read,
    // /console/inbox/events/{event_id}, /console/inbox/traces/{run_id},
    // /console/debug/backend-logs are implemented in ConsoleController.
    // Duplicate routes are intentionally not declared here to avoid Spring
    // ambiguous-mapping errors.

    // ===== CRON =====
    @GetMapping("/cron/dispatch-targets")
    public List<Map<String, String>> cronTargets() { return List.of(); }
    @GetMapping("/cron/jobs")
    public List<Map<String, String>> cronJobs() { return List.of(); }
    @PostMapping("/cron/jobs")
    public Map<String, String> cronJobCreate() { return Map.of("id", UUID.randomUUID().toString()); }
    @GetMapping("/cron/jobs/{job_id}")
    public Map<String, String> cronJobDetail(@PathVariable String job_id) { return Map.of("id", job_id); }
    @DeleteMapping("/cron/jobs/{job_id}")
    public Map<String, String> cronJobDelete(@PathVariable String job_id) { return Map.of("status", "ok"); }
    @PutMapping("/cron/jobs/{job_id}")
    public Map<String, String> cronJobUpdate(@PathVariable String job_id) { return Map.of("status", "ok"); }
    @GetMapping("/cron/jobs/{job_id}/history")
    public List<Map<String, String>> cronJobHistory(@PathVariable String job_id) { return List.of(); }
    @PostMapping("/cron/jobs/{job_id}/pause")
    public Map<String, String> cronJobPause(@PathVariable String job_id) { return Map.of("status", "ok"); }
    @PostMapping("/cron/jobs/{job_id}/resume")
    public Map<String, String> cronJobResume(@PathVariable String job_id) { return Map.of("status", "ok"); }
    @PostMapping("/cron/jobs/{job_id}/run")
    public Map<String, String> cronJobRun(@PathVariable String job_id) { return Map.of("status", "ok"); }
    @GetMapping("/cron/jobs/{job_id}/state")
    public Map<String, String> cronJobState(@PathVariable String job_id) { return Map.of("state", "idle"); }

    // ===== ENVS =====
    // ===== LOCAL MODELS =====
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

    // ===== LOOPS =====
    @DeleteMapping("/loops/custom/{mode_id}")
    public Map<String, String> loopDelete(@PathVariable String mode_id) { return Map.of("status", "ok"); }
    @PutMapping("/loops/custom/{mode_id}")
    public Map<String, String> loopUpdate(@PathVariable String mode_id) { return Map.of("status", "ok"); }
    @PostMapping("/loops/custom/{mode_id}/duplicate")
    public Map<String, String> loopDuplicate(@PathVariable String mode_id) { return Map.of("id", UUID.randomUUID().toString()); }

    // ===== MARKET =====
    @PostMapping("/market/search")
    public List<Map<String, String>> marketSearch() { return List.of(); }

    // OpenRouter endpoints (non-conflicting)
    @PostMapping("/models/openrouter/discover-extended")
    public List<Map<String, String>> openrouterDiscover() { return List.of(); }
    @PostMapping("/models/openrouter/models/filter")
    public List<Map<String, String>> openrouterFilter() { return List.of(); }
    @GetMapping("/models/openrouter/series")
    public List<Map<String, String>> openrouterSeries() { return List.of(); }

    // ===== PROVIDERS =====
    @GetMapping("/providers/{provider_id}/oauth/callback")
    public Map<String, String> providerOauthCallback(@PathVariable String provider_id) { return Map.of("status", "ok"); }

    // ===== TOOL CALLS =====
    @GetMapping("/tool-calls/{session_id}/{tool_call_id}")
    public Map<String, String> toolCallDetail(@PathVariable String session_id, @PathVariable String tool_call_id) { return Map.of(); }
    @PostMapping("/tool-calls/{session_id}/{tool_call_id}/cancel")
    public Map<String, String> toolCallCancel(@PathVariable String session_id, @PathVariable String tool_call_id) { return Map.of("status", "ok"); }
    @PostMapping("/tool-calls/{session_id}/{tool_call_id}/extend-deadline")
    public Map<String, String> toolCallExtend(@PathVariable String session_id, @PathVariable String tool_call_id) { return Map.of("status", "ok"); }
    @PostMapping("/tool-calls/{session_id}/{tool_call_id}/offload")
    public Map<String, String> toolCallOffload(@PathVariable String session_id, @PathVariable String tool_call_id) { return Map.of("status", "ok"); }
    @GetMapping("/tool-calls/{session_id}/{tool_call_id}/output")
    public Map<String, String> toolCallOutput(@PathVariable String session_id, @PathVariable String tool_call_id) { return Map.of("content", ""); }
    @GetMapping("/tool-calls/{session_id}/{tool_call_id}/stream")
    public Map<String, String> toolCallStream(@PathVariable String session_id, @PathVariable String tool_call_id) { return Map.of("status", "ok"); }

    // ===== TOOLS (implemented in ToolsController) =====

    // ===== WORKSPACE =====
    // === PRECISE METHOD GAPS ===

    @PutMapping("/agents/{agentId}/config/agents/llm-routing")
    public Map<String, String> agentLlmRoutingUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }

    @PostMapping("/agents/{agentId}/cron/jobs")
    public Map<String, String> agentCronJobCreate(@PathVariable String agentId) { return Map.of("id", UUID.randomUUID().toString()); }

    @PostMapping("/coding-mode")
    public Map<String, String> codingModeSet() { return Map.of("status", "ok"); }
    @GetMapping("/coding-mode")
    public Map<String, Object> codingModeGet() { return Map.of("enabled", false, "mode", "chat"); }

    @PostMapping("/loops/custom")
    public Map<String, String> loopsCustomCreate() { return Map.of("id", UUID.randomUUID().toString()); }

    @PostMapping("/providers/{provider_id}/oauth/start")
    public Map<String, Object> providerOauthStart(@PathVariable String provider_id) { return Map.of("auth_url", ""); }

    @PutMapping("/settings/language")
    public Map<String, String> languageUpdate() { return Map.of("status", "ok"); }

    @GetMapping("/loops")
    public List<Map<String, String>> loopsGet() { return List.of(); }
}
