package com.agent.coding.controller;

import org.springframework.web.bind.annotation.*;
import java.util.*;

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

    // ===== AGENT CONFIG: ACP =====
    @GetMapping("/agents/{agentId}/config/acp")
    public Map<String, String> agentAcp(@PathVariable String agentId) { return Map.of("enabled", "false"); }
    @PutMapping("/agents/{agentId}/config/acp")
    public Map<String, String> agentAcpUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/config/acp/{agent_name}")
    public Map<String, String> agentAcpAgent(@PathVariable String agentId, @PathVariable String agent_name) { return Map.of(); }
    @PutMapping("/agents/{agentId}/config/acp/{agent_name}")
    public Map<String, String> agentAcpAgentUpdate(@PathVariable String agentId, @PathVariable String agent_name) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/config/acp/node-runtime")
    public Map<String, String> agentAcpNode(@PathVariable String agentId) { return Map.of(); }
    @PutMapping("/agents/{agentId}/config/acp/node-runtime")
    public Map<String, String> agentAcpNodeUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }

    // ===== AGENT CONFIG: CHANNELS =====
    @GetMapping("/agents/{agentId}/config/channels/{channel_name}")
    public Map<String, String> agentChannelDetail(@PathVariable String agentId, @PathVariable String channel_name) { return Map.of("name", channel_name); }
    @PutMapping("/agents/{agentId}/config/channels/{channel_name}")
    public Map<String, String> agentChannelUpdate(@PathVariable String agentId, @PathVariable String channel_name) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/config/channels/{channel_name}/health")
    public Map<String, String> agentChannelHealth(@PathVariable String agentId, @PathVariable String channel_name) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/config/channels/{channel_name}/restart")
    public Map<String, String> agentChannelRestart(@PathVariable String agentId, @PathVariable String channel_name) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/config/channels/{channel}/qrcode")
    public Map<String, String> agentChannelQrcode(@PathVariable String agentId, @PathVariable String channel) { return Map.of("url", ""); }
    @GetMapping("/agents/{agentId}/config/channels/{channel}/qrcode/status")
    public Map<String, String> agentChannelQrcodeStatus(@PathVariable String agentId, @PathVariable String channel) { return Map.of("status", "pending"); }
    @GetMapping("/agents/{agentId}/config/channels/schemas")
    public List<Map<String, String>> agentChannelSchemas(@PathVariable String agentId) { return List.of(); }
    @GetMapping("/agents/{agentId}/config/channels/types")
    public List<Map<String, String>> agentChannelTypes(@PathVariable String agentId) { return List.of(); }

    // ===== AGENT CONFIG: HEARTBEAT =====
    @PostMapping("/agents/{agentId}/config/heartbeat/run")
    public Map<String, String> agentHeartbeatRun(@PathVariable String agentId) { return Map.of("status", "ok"); }

    // ===== AGENT CONFIG: SECURITY =====
    @GetMapping("/agents/{agentId}/config/security/allow-no-auth-hosts")
    public Map<String, Object> agentSecurityAuthHosts(@PathVariable String agentId) { return Map.of("enabled", false, "hosts", List.of()); }
    @PutMapping("/agents/{agentId}/config/security/allow-no-auth-hosts")
    public Map<String, String> agentSecurityAuthHostsUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/config/security/file-guard")
    public Map<String, Object> agentSecurityFileGuard(@PathVariable String agentId) { return Map.of("enabled", false); }
    @PutMapping("/agents/{agentId}/config/security/file-guard")
    public Map<String, String> agentSecurityFileGuardUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/config/security/sandbox")
    public Map<String, Object> agentSecuritySandbox(@PathVariable String agentId) { return Map.of("enabled", false); }
    @PutMapping("/agents/{agentId}/config/security/sandbox")
    public Map<String, String> agentSecuritySandboxUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/config/security/skill-scanner")
    public Map<String, Object> agentSecuritySkillScanner(@PathVariable String agentId) { return Map.of("enabled", false); }
    @PutMapping("/agents/{agentId}/config/security/skill-scanner")
    public Map<String, String> agentSecuritySkillScannerUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/config/security/skill-scanner/blocked-history")
    public List<Map<String, String>> agentSkillBlockedHistory(@PathVariable String agentId) { return List.of(); }
    @DeleteMapping("/agents/{agentId}/config/security/skill-scanner/blocked-history")
    public Map<String, String> agentSkillBlockedHistoryClear(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @DeleteMapping("/agents/{agentId}/config/security/skill-scanner/blocked-history/{index}")
    public Map<String, String> agentSkillBlockedHistoryDelete(@PathVariable String agentId, @PathVariable String index) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/config/security/skill-scanner/whitelist")
    public Map<String, String> agentSkillWhitelistAdd(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @DeleteMapping("/agents/{agentId}/config/security/skill-scanner/whitelist/{skill_name}")
    public Map<String, String> agentSkillWhitelistRemove(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/config/security/tool-guard")
    public Map<String, Object> agentSecurityToolGuard(@PathVariable String agentId) { return Map.of("enabled", false); }
    @PutMapping("/agents/{agentId}/config/security/tool-guard")
    public Map<String, String> agentSecurityToolGuardUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/config/security/tool-guard/builtin-rules")
    public List<Map<String, String>> agentToolGuardRules(@PathVariable String agentId) { return List.of(); }

    // ===== AGENT CHAT (agent-scoped extras) =====
    @PostMapping("/agents/{agentId}/chats/actions/batch-archive")
    public Map<String, String> agentBatchArchive(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/chats/actions/batch-unarchive")
    public Map<String, String> agentBatchUnarchive(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/console/chat/task")
    public Map<String, String> agentChatTask(@PathVariable String agentId) { return Map.of("task_id", UUID.randomUUID().toString()); }
    @GetMapping("/agents/{agentId}/console/chat/task/{task_id}")
    public Map<String, String> agentChatTaskStatus(@PathVariable String agentId, @PathVariable String task_id) { return Map.of("status", "completed"); }
    @GetMapping("/agents/{agentId}/console/debug/backend-logs")
    public List<Map<String, String>> agentBackendLogs(@PathVariable String agentId) { return List.of(); }
    @GetMapping("/agents/{agentId}/console/inbox/events")
    public List<Map<String, String>> agentInboxEvents(@PathVariable String agentId) { return List.of(); }
    @DeleteMapping("/agents/{agentId}/console/inbox/events/{event_id}")
    public Map<String, String> agentInboxEventDelete(@PathVariable String agentId, @PathVariable String event_id) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/console/inbox/read")
    public Map<String, String> agentInboxRead(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/console/inbox/traces/{run_id}")
    public Map<String, Object> agentInboxTrace(@PathVariable String agentId, @PathVariable String run_id) { return Map.of(); }
    @GetMapping("/agents/{agentId}/console/push-messages")
    public Map<String, Object> agentPushMessages(@PathVariable String agentId) { return Map.of("messages", List.of(), "pending_approvals", List.of()); }
    @PostMapping("/agents/{agentId}/console/upload")
    public Map<String, String> agentUpload(@PathVariable String agentId) { return Map.of("url", ""); }

    // ===== AGENT CRON =====
    @GetMapping("/agents/{agentId}/cron/dispatch-targets")
    public List<Map<String, String>> agentCronTargets(@PathVariable String agentId) { return List.of(); }
    @GetMapping("/agents/{agentId}/cron/jobs/{job_id}")
    public Map<String, String> agentCronJob(@PathVariable String agentId, @PathVariable String job_id) { return Map.of("id", job_id); }
    @DeleteMapping("/agents/{agentId}/cron/jobs/{job_id}")
    public Map<String, String> agentCronJobDelete(@PathVariable String agentId, @PathVariable String job_id) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/cron/jobs/{job_id}")
    public Map<String, String> agentCronJobUpdate(@PathVariable String agentId, @PathVariable String job_id) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/cron/jobs/{job_id}/history")
    public List<Map<String, String>> agentCronJobHistory(@PathVariable String agentId, @PathVariable String job_id) { return List.of(); }
    @PostMapping("/agents/{agentId}/cron/jobs/{job_id}/pause")
    public Map<String, String> agentCronJobPause(@PathVariable String agentId, @PathVariable String job_id) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/cron/jobs/{job_id}/resume")
    public Map<String, String> agentCronJobResume(@PathVariable String agentId, @PathVariable String job_id) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/cron/jobs/{job_id}/run")
    public Map<String, String> agentCronJobRun(@PathVariable String agentId, @PathVariable String job_id) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/cron/jobs/{job_id}/state")
    public Map<String, String> agentCronJobState(@PathVariable String agentId, @PathVariable String job_id) { return Map.of("state", "idle"); }

    // ===== AGENT MCP =====
    @GetMapping("/agents/{agentId}/mcp/{client_key}")
    public Map<String, String> agentMcpDetail(@PathVariable String agentId, @PathVariable String client_key) { return Map.of(); }
    @DeleteMapping("/agents/{agentId}/mcp/{client_key}")
    public Map<String, String> agentMcpDelete(@PathVariable String agentId, @PathVariable String client_key) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/mcp/{client_key}")
    public Map<String, String> agentMcpUpdate(@PathVariable String agentId, @PathVariable String client_key) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/mcp/access-principals")
    public List<Map<String, String>> agentMcpPrincipals(@PathVariable String agentId) { return List.of(); }
    @GetMapping("/agents/{agentId}/mcp/oauth/callback")
    public Map<String, String> agentMcpOauthCallback(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/mcp/oauth/start/{client_key}")
    public Map<String, String> agentMcpOauthStart(@PathVariable String agentId, @PathVariable String client_key) { return Map.of("auth_url", ""); }
    @GetMapping("/agents/{agentId}/mcp/oauth/status/{client_key}")
    public Map<String, String> agentMcpOauthStatus(@PathVariable String agentId, @PathVariable String client_key) { return Map.of("status", "pending"); }
    @DeleteMapping("/agents/{agentId}/mcp/oauth/{client_key}")
    public Map<String, String> agentMcpOauthDelete(@PathVariable String agentId, @PathVariable String client_key) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/mcp/policy/{client_key}")
    public Map<String, String> agentMcpPolicy(@PathVariable String agentId, @PathVariable String client_key) { return Map.of(); }
    @PutMapping("/agents/{agentId}/mcp/policy/{client_key}")
    public Map<String, String> agentMcpPolicyUpdate(@PathVariable String agentId, @PathVariable String client_key) { return Map.of("status", "ok"); }
    @PatchMapping("/agents/{agentId}/mcp/toggle/{client_key}")
    public Map<String, String> agentMcpToggle(@PathVariable String agentId, @PathVariable String client_key) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/mcp/tools/{client_key}")
    public List<Map<String, String>> agentMcpTools(@PathVariable String agentId, @PathVariable String client_key) { return List.of(); }
    @PutMapping("/agents/{agentId}/mcp/tools/{client_key}")
    public Map<String, String> agentMcpToolsUpdate(@PathVariable String agentId, @PathVariable String client_key) { return Map.of("status", "ok"); }

    // ===== AGENT MEMORY =====
    @PostMapping("/agents/{agentId}/memory/reindex")
    public Map<String, String> agentMemoryReindex(@PathVariable String agentId) { return Map.of("status", "ok"); }

    // ===== AGENT PLUGINS =====
    @DeleteMapping("/agents/{agentId}/plugins/{plugin_id}")
    public Map<String, String> agentPluginDelete(@PathVariable String agentId, @PathVariable String plugin_id) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/plugins/{plugin_id}/files/{file_path}")
    public Map<String, String> agentPluginFile(@PathVariable String agentId, @PathVariable String plugin_id, @PathVariable String file_path) { return Map.of("content", ""); }
    @GetMapping("/agents/{agentId}/plugins/{plugin_id}/status")
    public Map<String, String> agentPluginStatus(@PathVariable String agentId, @PathVariable String plugin_id) { return Map.of("status", "active"); }
    @PostMapping("/agents/{agentId}/plugins/install")
    public Map<String, String> agentPluginInstall(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/plugins/upload")
    public Map<String, String> agentPluginUpload(@PathVariable String agentId) { return Map.of("status", "ok"); }

    // ===== AGENT SKILLS =====
    @DeleteMapping("/agents/{agentId}/skills/{skill_name}")
    public Map<String, String> agentSkillDelete(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/skills/{skill_name}/channels")
    public Map<String, String> agentSkillChannels(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/skills/{skill_name}/config")
    public Map<String, String> agentSkillConfig(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of(); }
    @DeleteMapping("/agents/{agentId}/skills/{skill_name}/config")
    public Map<String, String> agentSkillConfigDelete(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/skills/{skill_name}/config")
    public Map<String, String> agentSkillConfigUpdate(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/{skill_name}/disable")
    public Map<String, String> agentSkillDisable(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/{skill_name}/enable")
    public Map<String, String> agentSkillEnable(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/skills/{skill_name}/files/{file_path}")
    public Map<String, String> agentSkillFile(@PathVariable String agentId, @PathVariable String skill_name, @PathVariable String file_path) { return Map.of("content", ""); }
    @PutMapping("/agents/{agentId}/skills/{skill_name}/tags")
    public Map<String, String> agentSkillTags(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/batch-delete")
    public Map<String, String> agentBatchDeleteSkills(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/batch-disable")
    public Map<String, String> agentBatchDisableSkills(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/batch-enable")
    public Map<String, String> agentBatchEnableSkills(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/hub/install/cancel/{task_id}")
    public Map<String, String> agentSkillHubCancel(@PathVariable String agentId, @PathVariable String task_id) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/hub/install/start")
    public Map<String, String> agentSkillHubInstall(@PathVariable String agentId) { return Map.of("task_id", UUID.randomUUID().toString()); }
    @GetMapping("/agents/{agentId}/skills/hub/install/status/{task_id}")
    public Map<String, String> agentSkillHubStatus(@PathVariable String agentId, @PathVariable String task_id) { return Map.of("status", "completed"); }
    @GetMapping("/agents/{agentId}/skills/hub/search")
    public List<Map<String, String>> agentSkillHubSearch(@PathVariable String agentId) { return List.of(); }
    @GetMapping("/agents/{agentId}/skills/pool")
    public List<Map<String, String>> agentSkillPool(@PathVariable String agentId) { return List.of(); }
    @DeleteMapping("/agents/{agentId}/skills/pool/{skill_name}")
    public Map<String, String> agentSkillPoolDelete(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/skills/pool/{skill_name}/auto-update")
    public Map<String, String> agentSkillPoolAutoUpdate(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/skills/pool/{skill_name}/config")
    public Map<String, String> agentSkillPoolConfig(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of(); }
    @DeleteMapping("/agents/{agentId}/skills/pool/{skill_name}/config")
    public Map<String, String> agentSkillPoolConfigDelete(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/skills/pool/{skill_name}/config")
    public Map<String, String> agentSkillPoolConfigUpdate(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/skills/pool/{skill_name}/tags")
    public Map<String, String> agentSkillPoolTags(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/pool/{skill_name}/update-builtin")
    public Map<String, String> agentSkillPoolUpdateBuiltin(@PathVariable String agentId, @PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/pool/batch-delete")
    public Map<String, String> agentSkillPoolBatchDelete(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/skills/pool/builtin-notice")
    public Map<String, String> agentSkillPoolBuiltinNotice(@PathVariable String agentId) { return Map.of("notice", ""); }
    @GetMapping("/agents/{agentId}/skills/pool/builtin-sources")
    public List<Map<String, String>> agentSkillPoolBuiltinSources(@PathVariable String agentId) { return List.of(); }
    @PostMapping("/agents/{agentId}/skills/pool/create")
    public Map<String, String> agentSkillPoolCreate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/pool/download")
    public Map<String, String> agentSkillPoolDownload(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/pool/import")
    public Map<String, String> agentSkillPoolImport(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/pool/import-builtin")
    public Map<String, String> agentSkillPoolImportBuiltin(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/pool/refresh")
    public Map<String, String> agentSkillPoolRefresh(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/skills/pool/save")
    public Map<String, String> agentSkillPoolSave(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/pool/upload")
    public Map<String, String> agentSkillPoolUpload(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/pool/upload-zip")
    public Map<String, String> agentSkillPoolUploadZip(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/refresh")
    public Map<String, String> agentSkillRefresh(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/skills/save")
    public Map<String, String> agentSkillSave(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/skills/upload")
    public Map<String, String> agentSkillUpload(@PathVariable String agentId) { return Map.of("status", "ok"); }

    // ===== AGENT TOOLS =====
    @PatchMapping("/agents/{agentId}/tools/{tool_name}/async-execution")
    public Map<String, String> agentToolAsyncExec(@PathVariable String agentId, @PathVariable String tool_name) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/tools/{tool_name}/config")
    public Map<String, String> agentToolConfig(@PathVariable String agentId, @PathVariable String tool_name) { return Map.of(); }
    @PostMapping("/agents/{agentId}/tools/{tool_name}/config")
    public Map<String, String> agentToolConfigSave(@PathVariable String agentId, @PathVariable String tool_name) { return Map.of("status", "ok"); }
    @PatchMapping("/agents/{agentId}/tools/{tool_name}/toggle")
    public Map<String, String> agentToolToggle(@PathVariable String agentId, @PathVariable String tool_name) { return Map.of("status", "ok"); }

    // ===== AGENT WORKSPACE =====
    @GetMapping("/agents/{agentId}/workspace/audio-mode")
    public Map<String, String> agentAudioMode(@PathVariable String agentId) { return Map.of("enabled", "false"); }
    @PutMapping("/agents/{agentId}/workspace/audio-mode")
    public Map<String, String> agentAudioModeUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/workspace/binary-files/{file_path}")
    public Map<String, String> agentBinaryFile(@PathVariable String agentId, @PathVariable String file_path) { return Map.of("url", ""); }
    @GetMapping("/agents/{agentId}/workspace/code-files/{file_path}")
    public Map<String, String> agentCodeFile(@PathVariable String agentId, @PathVariable String file_path) { return Map.of("content", ""); }
    @PutMapping("/agents/{agentId}/workspace/code-files/{file_path}")
    public Map<String, String> agentCodeFileSave(@PathVariable String agentId, @PathVariable String file_path) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/workspace/files/{md_name}")
    public Map<String, String> agentMdFile(@PathVariable String agentId, @PathVariable String md_name) { return Map.of("content", ""); }
    @PutMapping("/agents/{agentId}/workspace/files/{md_name}")
    public Map<String, String> agentMdFileSave(@PathVariable String agentId, @PathVariable String md_name) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/workspace/local-whisper-status")
    public Map<String, String> agentWhisperStatus(@PathVariable String agentId) { return Map.of("available", "false"); }
    @GetMapping("/agents/{agentId}/workspace/memory/{md_path}")
    public Map<String, String> agentMemoryFile(@PathVariable String agentId, @PathVariable String md_path) { return Map.of("content", ""); }
    @PutMapping("/agents/{agentId}/workspace/memory/{md_path}")
    public Map<String, String> agentMemoryFileSave(@PathVariable String agentId, @PathVariable String md_path) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/workspace/transcribe")
    public Map<String, String> agentTranscribe(@PathVariable String agentId) { return Map.of("text", ""); }
    @GetMapping("/agents/{agentId}/workspace/transcription-provider")
    public Map<String, String> agentTranscriptionProvider(@PathVariable String agentId) { return Map.of("provider", "none"); }
    @PutMapping("/agents/{agentId}/workspace/transcription-provider")
    public Map<String, String> agentTranscriptionProviderSet(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @GetMapping("/agents/{agentId}/workspace/transcription-providers")
    public List<Map<String, String>> agentTranscriptionProviders(@PathVariable String agentId) { return List.of(); }
    @GetMapping("/agents/{agentId}/workspace/transcription-provider-type")
    public Map<String, String> agentTranscriptionProviderType(@PathVariable String agentId) { return Map.of("type", "none"); }
    @PutMapping("/agents/{agentId}/workspace/transcription-provider-type")
    public Map<String, String> agentTranscriptionProviderTypeSet(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PostMapping("/agents/{agentId}/workspace/upload")
    public Map<String, String> agentWorkspaceUpload(@PathVariable String agentId) { return Map.of("url", ""); }

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
    @PostMapping("/chats/actions/batch-archive")
    public Map<String, String> chatsBatchArchive() { return Map.of("status", "ok"); }
    @PostMapping("/chats/actions/batch-unarchive")
    public Map<String, String> chatsBatchUnarchive() { return Map.of("status", "ok"); }

    // ===== CONFIG (global) =====
    @GetMapping("/config/acp")
    public Map<String, String> configAcp() { return Map.of("enabled", "false"); }
    @PutMapping("/config/acp")
    public Map<String, String> configAcpUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/config/acp/{agent_name}")
    public Map<String, String> configAcpAgent(@PathVariable String agent_name) { return Map.of(); }
    @PutMapping("/config/acp/{agent_name}")
    public Map<String, String> configAcpAgentUpdate(@PathVariable String agent_name) { return Map.of("status", "ok"); }
    @GetMapping("/config/acp/node-runtime")
    public Map<String, String> configAcpNode() { return Map.of(); }
    @PutMapping("/config/acp/node-runtime")
    public Map<String, String> configAcpNodeUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/config/agents/llm-routing")
    public Map<String, String> configLlmRouting() { return Map.of("mode", "default"); }
    @PutMapping("/config/agents/llm-routing")
    public Map<String, String> configLlmRoutingUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/config/channels/{channel_name}")
    public Map<String, String> configChannelDetail(@PathVariable String channel_name) { return Map.of("name", channel_name); }
    @PutMapping("/config/channels/{channel_name}")
    public Map<String, String> configChannelUpdate(@PathVariable String channel_name) { return Map.of("status", "ok"); }
    @GetMapping("/config/channels/{channel_name}/health")
    public Map<String, String> configChannelHealth(@PathVariable String channel_name) { return Map.of("status", "ok"); }
    @PostMapping("/config/channels/{channel_name}/restart")
    public Map<String, String> configChannelRestart(@PathVariable String channel_name) { return Map.of("status", "ok"); }
    @GetMapping("/config/channels/{channel}/qrcode")
    public Map<String, String> configChannelQrcode(@PathVariable String channel) { return Map.of("url", ""); }
    @GetMapping("/config/channels/{channel}/qrcode/status")
    public Map<String, String> configChannelQrcodeStatus(@PathVariable String channel) { return Map.of("status", "pending"); }
    @GetMapping("/config/channels/schemas")
    public List<Map<String, String>> configChannelSchemas() { return List.of(); }
    @GetMapping("/config/channels/types")
    public List<Map<String, String>> configChannelTypes() { return List.of(); }
    @PostMapping("/config/heartbeat/run")
    public Map<String, String> configHeartbeatRun() { return Map.of("status", "ok"); }
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
    @GetMapping("/config/user-timezone")
    public Map<String, String> configUserTimezone() { return Map.of("timezone", "Asia/Shanghai"); }
    @PutMapping("/config/user-timezone")
    public Map<String, String> configUserTimezoneUpdate() { return Map.of("status", "ok"); }

    // ===== CONSOLE =====
    @GetMapping("/console/debug/backend-logs")
    public List<Map<String, String>> consoleBackendLogs() { return List.of(); }
    @DeleteMapping("/console/inbox/events/{event_id}")
    public Map<String, String> consoleInboxDelete(@PathVariable String event_id) { return Map.of("status", "ok"); }
    @GetMapping("/console/inbox/traces/{run_id}")
    public Map<String, Object> consoleInboxTrace(@PathVariable String run_id) { return Map.of(); }

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
    @DeleteMapping("/envs/{key}")
    public Map<String, String> envDelete(@PathVariable String key) { return Map.of("status", "ok"); }

    // ===== FRONTEND PLUGIN =====
    @GetMapping("/frontend_plugin/{plugin_id}/files/{file_path}")
    public Map<String, String> frontendPluginFile(@PathVariable String plugin_id, @PathVariable String file_path) { return Map.of("content", ""); }

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

    // ===== MCP (global) =====
    @GetMapping("/mcp/{client_key}")
    public Map<String, String> mcpDetail(@PathVariable String client_key) { return Map.of(); }
    @DeleteMapping("/mcp/{client_key}")
    public Map<String, String> mcpDelete(@PathVariable String client_key) { return Map.of("status", "ok"); }
    @PutMapping("/mcp/{client_key}")
    public Map<String, String> mcpUpdate(@PathVariable String client_key) { return Map.of("status", "ok"); }
    @GetMapping("/mcp/access-principals")
    public List<Map<String, String>> mcpPrincipals() { return List.of(); }
    @GetMapping("/mcp/oauth/callback")
    public Map<String, String> mcpOauthCallback() { return Map.of("status", "ok"); }
    @PostMapping("/mcp/oauth/start/{client_key}")
    public Map<String, String> mcpOauthStart(@PathVariable String client_key) { return Map.of("auth_url", ""); }
    @GetMapping("/mcp/oauth/status/{client_key}")
    public Map<String, String> mcpOauthStatus(@PathVariable String client_key) { return Map.of("status", "pending"); }
    @DeleteMapping("/mcp/oauth/{client_key}")
    public Map<String, String> mcpOauthDelete(@PathVariable String client_key) { return Map.of("status", "ok"); }
    @GetMapping("/mcp/policy/{client_key}")
    public Map<String, String> mcpPolicy(@PathVariable String client_key) { return Map.of(); }
    @PutMapping("/mcp/policy/{client_key}")
    public Map<String, String> mcpPolicyUpdate(@PathVariable String client_key) { return Map.of("status", "ok"); }
    @PatchMapping("/mcp/toggle/{client_key}")
    public Map<String, String> mcpToggle(@PathVariable String client_key) { return Map.of("status", "ok"); }
    @GetMapping("/mcp/tools/{client_key}")
    public List<Map<String, String>> mcpTools(@PathVariable String client_key) { return List.of(); }
    @PutMapping("/mcp/tools/{client_key}")
    public Map<String, String> mcpToolsUpdate(@PathVariable String client_key) { return Map.of("status", "ok"); }

    // OpenRouter endpoints (non-conflicting)
    @PostMapping("/models/openrouter/discover-extended")
    public List<Map<String, String>> openrouterDiscover() { return List.of(); }
    @PostMapping("/models/openrouter/models/filter")
    public List<Map<String, String>> openrouterFilter() { return List.of(); }
    @GetMapping("/models/openrouter/series")
    public List<Map<String, String>> openrouterSeries() { return List.of(); }

    // ===== PAWAPPS =====
    @GetMapping("/pawapps/{app_id}")
    public Map<String, String> pawappDetail(@PathVariable String app_id) { return Map.of("id", app_id); }
    @DeleteMapping("/pawapps/{app_id}")
    public Map<String, String> pawappDelete(@PathVariable String app_id) { return Map.of("status", "ok"); }
    @GetMapping("/pawapps/{app_id}/settings")
    public Map<String, String> pawappSettings(@PathVariable String app_id) { return Map.of(); }
    @GetMapping("/pawapps/{app_id}/static/{file_path}")
    public Map<String, String> pawappStatic(@PathVariable String app_id, @PathVariable String file_path) { return Map.of("content", ""); }

    // ===== PLUGINS =====
    @DeleteMapping("/plugins/{plugin_id}")
    public Map<String, String> pluginDelete(@PathVariable String plugin_id) { return Map.of("status", "ok"); }
    @GetMapping("/plugins/{plugin_id}/files/{file_path}")
    public Map<String, String> pluginFile(@PathVariable String plugin_id, @PathVariable String file_path) { return Map.of("content", ""); }
    @GetMapping("/plugins/{plugin_id}/status")
    public Map<String, String> pluginStatus(@PathVariable String plugin_id) { return Map.of("status", "active"); }
    @PostMapping("/plugins/install")
    public Map<String, String> pluginInstall() { return Map.of("status", "ok"); }
    @PostMapping("/plugins/upload")
    public Map<String, String> pluginUpload() { return Map.of("status", "ok"); }

    // ===== PROVIDERS =====
    @GetMapping("/providers/{provider_id}/oauth/callback")
    public Map<String, String> providerOauthCallback(@PathVariable String provider_id) { return Map.of("status", "ok"); }

    // ===== SKILLS =====
    @DeleteMapping("/skills/{skill_name}")
    public Map<String, String> skillDelete(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PutMapping("/skills/{skill_name}/channels")
    public Map<String, String> skillChannels(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @GetMapping("/skills/{skill_name}/config")
    public Map<String, String> skillConfig(@PathVariable String skill_name) { return Map.of(); }
    @DeleteMapping("/skills/{skill_name}/config")
    public Map<String, String> skillConfigDelete(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PutMapping("/skills/{skill_name}/config")
    public Map<String, String> skillConfigUpdate(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PostMapping("/skills/{skill_name}/disable")
    public Map<String, String> skillDisable(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PostMapping("/skills/{skill_name}/enable")
    public Map<String, String> skillEnable(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @GetMapping("/skills/{skill_name}/files/{file_path}")
    public Map<String, String> skillFile(@PathVariable String skill_name, @PathVariable String file_path) { return Map.of("content", ""); }
    @PutMapping("/skills/{skill_name}/tags")
    public Map<String, String> skillTags(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PostMapping("/skills/ai/optimize/stream")
    public Map<String, String> skillAiOptimize() { return Map.of("status", "ok"); }
    @PostMapping("/skills/batch-delete")
    public Map<String, String> skillBatchDelete() { return Map.of("status", "ok"); }
    @PostMapping("/skills/batch-disable")
    public Map<String, String> skillBatchDisable() { return Map.of("status", "ok"); }
    @PostMapping("/skills/batch-enable")
    public Map<String, String> skillBatchEnable() { return Map.of("status", "ok"); }
    @PostMapping("/skills/hub/install/cancel/{task_id}")
    public Map<String, String> skillHubCancel(@PathVariable String task_id) { return Map.of("status", "ok"); }
    @PostMapping("/skills/hub/install/start")
    public Map<String, String> skillHubInstall() { return Map.of("task_id", UUID.randomUUID().toString()); }
    @GetMapping("/skills/hub/install/status/{task_id}")
    public Map<String, String> skillHubStatus(@PathVariable String task_id) { return Map.of("status", "completed"); }
    @GetMapping("/skills/pool")
    public List<Map<String, String>> skillPool() { return List.of(); }
    @DeleteMapping("/skills/pool/{skill_name}")
    public Map<String, String> skillPoolDelete(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PutMapping("/skills/pool/{skill_name}/auto-update")
    public Map<String, String> skillPoolAutoUpdate(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @GetMapping("/skills/pool/{skill_name}/config")
    public Map<String, String> skillPoolConfig(@PathVariable String skill_name) { return Map.of(); }
    @DeleteMapping("/skills/pool/{skill_name}/config")
    public Map<String, String> skillPoolConfigDelete(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PutMapping("/skills/pool/{skill_name}/config")
    public Map<String, String> skillPoolConfigUpdate(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PutMapping("/skills/pool/{skill_name}/tags")
    public Map<String, String> skillPoolTags(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PostMapping("/skills/pool/{skill_name}/update-builtin")
    public Map<String, String> skillPoolUpdateBuiltin(@PathVariable String skill_name) { return Map.of("status", "ok"); }
    @PostMapping("/skills/pool/batch-delete")
    public Map<String, String> skillPoolBatchDelete() { return Map.of("status", "ok"); }
    @PostMapping("/skills/pool/create")
    public Map<String, String> skillPoolCreate() { return Map.of("status", "ok"); }
    @PostMapping("/skills/pool/download")
    public Map<String, String> skillPoolDownload() { return Map.of("status", "ok"); }
    @PostMapping("/skills/pool/import")
    public Map<String, String> skillPoolImport() { return Map.of("status", "ok"); }
    @PostMapping("/skills/pool/import-builtin")
    public Map<String, String> skillPoolImportBuiltin() { return Map.of("status", "ok"); }
    @PostMapping("/skills/pool/refresh")
    public Map<String, String> skillPoolRefresh() { return Map.of("status", "ok"); }
    @PutMapping("/skills/pool/save")
    public Map<String, String> skillPoolSave() { return Map.of("status", "ok"); }
    @PostMapping("/skills/pool/upload")
    public Map<String, String> skillPoolUpload() { return Map.of("status", "ok"); }
    @PostMapping("/skills/pool/upload-zip")
    public Map<String, String> skillPoolUploadZip() { return Map.of("status", "ok"); }
    @PostMapping("/skills/refresh")
    public Map<String, String> skillRefresh() { return Map.of("status", "ok"); }
    @PutMapping("/skills/save")
    public Map<String, String> skillSave() { return Map.of("status", "ok"); }
    @PostMapping("/skills/upload")
    public Map<String, String> skillUpload() { return Map.of("status", "ok"); }

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

    // ===== TOOLS =====
    @PatchMapping("/tools/{tool_name}/async-execution")
    public Map<String, String> toolAsyncExec(@PathVariable String tool_name) { return Map.of("status", "ok"); }
    @GetMapping("/tools/{tool_name}/config")
    public Map<String, String> toolConfig(@PathVariable String tool_name) { return Map.of(); }
    @PostMapping("/tools/{tool_name}/config")
    public Map<String, String> toolConfigSave(@PathVariable String tool_name) { return Map.of("status", "ok"); }
    @PatchMapping("/tools/{tool_name}/toggle")
    public Map<String, String> toolToggle(@PathVariable String tool_name) { return Map.of("status", "ok"); }

    // ===== WORKSPACE =====
    @PutMapping("/workspace/audio-mode")
    public Map<String, String> workspaceAudioModeUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/workspace/binary-files/{file_path}")
    public Map<String, String> workspaceBinaryFile(@PathVariable String file_path) { return Map.of("url", ""); }
    @PostMapping("/workspace/coding-project/upload-zip")
    public Map<String, String> workspaceCodingUploadZip() { return Map.of("id", UUID.randomUUID().toString()); }
    @PutMapping("/workspace/language")
    public Map<String, String> workspaceLanguageUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/workspace/memory/{md_path}")
    public Map<String, String> workspaceMemoryFile(@PathVariable String md_path) { return Map.of("content", ""); }
    @PutMapping("/workspace/memory/{md_path}")
    public Map<String, String> workspaceMemoryFileSave(@PathVariable String md_path) { return Map.of("status", "ok"); }
    @PutMapping("/workspace/running-config")
    public Map<String, String> workspaceRunningConfigUpdate() { return Map.of("status", "ok"); }
    @PutMapping("/workspace/system-prompt-files")
    public Map<String, String> workspaceSystemPromptUpdate() { return Map.of("status", "ok"); }
    @PutMapping("/workspace/transcription-provider")
    public Map<String, String> workspaceTranscriptionProviderSet() { return Map.of("status", "ok"); }
    @PutMapping("/workspace/transcription-provider-type")
    public Map<String, String> workspaceTranscriptionProviderTypeSet() { return Map.of("status", "ok"); }
    @GetMapping("/workspace/transcription-providers")
    public List<Map<String, String>> workspaceTranscriptionProviders() { return List.of(); }

    // === PRECISE METHOD GAPS ===

    @PostMapping("/agents")
    public Map<String, String> agentsCreate() { return Map.of("id", "default"); }
    @DeleteMapping("/agents/{agentId}")
    public Map<String, String> agentsDelete(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}")
    public Map<String, String> agentsUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }

    @PutMapping("/agents/{agentId}/config/agents/llm-routing")
    public Map<String, String> agentLlmRoutingUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/config/channels")
    public Map<String, String> agentChannelsUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/config/heartbeat")
    public Map<String, String> agentHeartbeatUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/config/user-timezone")
    public Map<String, String> agentTimezoneUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }

    @PostMapping("/agents/{agentId}/cron/jobs")
    public Map<String, String> agentCronJobCreate(@PathVariable String agentId) { return Map.of("id", UUID.randomUUID().toString()); }
    @PostMapping("/agents/{agentId}/mcp")
    public Map<String, String> agentMcpCreate(@PathVariable String agentId) { return Map.of("status", "ok"); }

    @PutMapping("/agents/{agentId}/workspace/language")
    public Map<String, String> agentLanguageUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/workspace/running-config")
    public Map<String, String> agentRunningConfigUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/workspace/system-prompt-files")
    public Map<String, String> agentSystemPromptUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }

    @PutMapping("/chats/{chat_id}")
    public Map<String, String> chatsUpdate(@PathVariable String chat_id) { return Map.of("status", "ok"); }

    @PostMapping("/coding-mode")
    public Map<String, String> codingModeSet() { return Map.of("status", "ok"); }
    @GetMapping("/coding-mode")
    public Map<String, Object> codingModeGet() { return Map.of("enabled", false, "mode", "chat"); }

    @PutMapping("/config/channels")
    public Map<String, String> configChannelsUpdate() { return Map.of("status", "ok"); }
    @PutMapping("/config/heartbeat")
    public Map<String, String> configHeartbeatUpdate() { return Map.of("status", "ok"); }

    @PutMapping("/envs")
    public Map<String, String> envsUpdate() { return Map.of("status", "ok"); }

    @PostMapping("/loops/custom")
    public Map<String, String> loopsCustomCreate() { return Map.of("id", UUID.randomUUID().toString()); }

    @PostMapping("/mcp")
    public Map<String, String> mcpCreate() { return Map.of("status", "ok"); }

    @PostMapping("/providers/{provider_id}/oauth/start")
    public Map<String, Object> providerOauthStart(@PathVariable String provider_id) { return Map.of("auth_url", ""); }

    @PutMapping("/settings/language")
    public Map<String, String> languageUpdate() { return Map.of("status", "ok"); }

    @PostMapping("/skills")
    public Map<String, String> skillsCreate() { return Map.of("status", "ok"); }

    @PutMapping("/workspace/coding-project")
    public Map<String, String> codingProjectUpdate() { return Map.of("status", "ok"); }

    @GetMapping("/workspace/transcription-provider-type")
    public Map<String, String> workspaceTranscriptionProviderType() { return Map.of("type", "none"); }
    @GetMapping("/workspace/running-config")
    public Map<String, Object> workspaceRunningConfigGet() { return Map.of(); }
    @GetMapping("/frontend_plugin")
    public List<Map<String, String>> frontendPlugins() { return List.of(); }
    @GetMapping("/loops")
    public List<Map<String, String>> loopsGet() { return List.of(); }
}
