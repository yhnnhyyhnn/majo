package com.agent.coding.controller;

import com.agent.coding.SettingsService;
import com.agent.coding.skill.SkillStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class FullCompatController {

    private static final Logger log = LoggerFactory.getLogger(FullCompatController.class);

    private static final int MAX_DEBUG_LOG_LINES = 1000;
    private static final int DEBUG_LOG_MAX_TAIL_BYTES = 512 * 1024;

    private final SettingsService settingsService;

    public FullCompatController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

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
    public Map<String, Object> agentAcp(@PathVariable String agentId) { return acpConfigFor(agentId); }
    @PutMapping("/agents/{agentId}/config/acp")
    public ResponseEntity<?> agentAcpUpdate(@PathVariable String agentId,
                                             @RequestBody Map<String, Object> body) { return saveAcpConfig(agentId, body); }
    @GetMapping("/agents/{agentId}/config/acp/{agent_name}")
    public ResponseEntity<?> agentAcpAgent(@PathVariable String agentId, @PathVariable String agent_name) {
        return acpAgentConfigFor(agentId, agent_name);
    }
    @PutMapping("/agents/{agentId}/config/acp/{agent_name}")
    public ResponseEntity<?> agentAcpAgentUpdate(@PathVariable String agentId, @PathVariable String agent_name,
                                                  @RequestBody Map<String, Object> body) {
        return saveAcpAgentConfig(agentId, agent_name, body);
    }
    @GetMapping("/agents/{agentId}/config/acp/node-runtime")
    public Map<String, Object> agentAcpNode(@PathVariable String agentId) { return configAcpNode(); }
    @PutMapping("/agents/{agentId}/config/acp/node-runtime")
    public ResponseEntity<?> agentAcpNodeUpdate(@PathVariable String agentId,
                                                 @RequestBody Map<String, Object> body) { return configAcpNodeUpdate(body); }

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
    public Map<String, Object> agentBackendLogs(@PathVariable String agentId,
                                                @RequestParam(defaultValue = "200") int lines) { return consoleBackendLogs(lines); }
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
    @GetMapping("/agents/{agentId}/plugins")
    public List<Map<String, String>> agentPlugins(@PathVariable String agentId) { return List.of(); }
    @GetMapping("/agents/{agentId}/plugins/catalog")
    public List<Map<String, String>> agentPluginCatalog(@PathVariable String agentId) { return List.of(); }
    @GetMapping("/agents/{agentId}/plugins/market/search")
    public List<Map<String, String>> agentPluginMarket(@PathVariable String agentId) { return List.of(); }

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
    @GetMapping("/config/acp")
    public Map<String, Object> configAcp(HttpServletRequest request) {
        return acpConfigFor(resolveAgentId(request));
    }
    @PutMapping("/config/acp")
    public ResponseEntity<?> configAcpUpdate(@RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        return saveAcpConfig(resolveAgentId(request), body);
    }
    @GetMapping("/config/acp/{agent_name}")
    public ResponseEntity<?> configAcpAgent(@PathVariable String agent_name,
                                            HttpServletRequest request) {
        return acpAgentConfigFor(resolveAgentId(request), agent_name);
    }
    @PutMapping("/config/acp/{agent_name}")
    public ResponseEntity<?> configAcpAgentUpdate(@PathVariable String agent_name,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        return saveAcpAgentConfig(resolveAgentId(request), agent_name, body);
    }
    @GetMapping("/config/acp/node-runtime")
    public Map<String, Object> configAcpNode() {
        return com.agent.coding.acp.ACPNodeRuntime.getNodeRuntimeStatus(
            com.agent.coding.agent.AgentStore.getGlobalACPNodePath());
    }
    @PutMapping("/config/acp/node-runtime")
    public ResponseEntity<?> configAcpNodeUpdate(@RequestBody Map<String, Object> body) {
        String nodePath = Objects.toString(body.get("node_path"), "").trim();
        if (!nodePath.isEmpty()) {
            Map<String, Object> candidate =
                com.agent.coding.acp.ACPNodeRuntime.resolveNodeRuntime(nodePath);
            if (!Boolean.TRUE.equals(candidate.get("available"))) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("reason_code", candidate.get("reason_code"));
                detail.put("reason", candidate.get("reason"));
                return ResponseEntity.badRequest().body(Map.of("detail", detail));
            }
        }
        com.agent.coding.agent.AgentStore.setGlobalACPNodePath(nodePath);
        return ResponseEntity.ok(com.agent.coding.acp.ACPNodeRuntime.getNodeRuntimeStatus(nodePath));
    }
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
    @GetMapping("/config/user-timezone")
    public Map<String, String> configUserTimezone(HttpServletRequest request) {
        return Map.of("timezone", resolveUserTimezone(resolveAgentId(request)));
    }
    @PutMapping("/config/user-timezone")
    public ResponseEntity<?> configUserTimezoneUpdate(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        return updateUserTimezone(resolveAgentId(request), body);
    }

    private String resolveAgentId(HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getParameter("agent");
        }
        return (agentId == null || agentId.isBlank()) ? "default" : agentId;
    }

    private String resolveUserTimezone(String agentId) {
        return com.agent.coding.agent.AgentStore.getUserTimezone(
            agentId, settingsService.getUserTimezone());
    }

    private ResponseEntity<?> updateUserTimezone(String agentId, Map<String, Object> body) {
        String tz = Objects.toString(body.get("timezone"), "").trim();
        if (tz.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "timezone is required"));
        }
        String resolved = normalizeTimezone(tz);
        if (resolved == null) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Invalid IANA timezone: '" + tz + "'"));
        }
        com.agent.coding.agent.AgentStore.setUserTimezone(agentId, resolved);
        return ResponseEntity.ok(Map.of("timezone", resolved));
    }

    // ===== CONSOLE =====
    @GetMapping("/console/debug/backend-logs")
    public Map<String, Object> consoleBackendLogs(@RequestParam(defaultValue = "200") int lines) {
        int clamped = Math.max(20, Math.min(lines, MAX_DEBUG_LOG_LINES));
        Path logPath = SkillStore.WORKING_DIR.resolve("majo.log").toAbsolutePath().normalize();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", logPath.toString());
        result.put("lines", clamped);
        try {
            BasicFileAttributes attrs = Files.readAttributes(logPath, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                return missingLogFile(result);
            }
            result.put("exists", true);
            result.put("updated_at", attrs.lastModifiedTime().toInstant().getEpochSecond());
            result.put("size", attrs.size());
            result.put("content", tailTextFile(logPath, clamped));
        } catch (IOException e) {
            return missingLogFile(result);
        }
        return result;
    }

    private Map<String, Object> missingLogFile(Map<String, Object> result) {
        result.put("exists", false);
        result.put("updated_at", null);
        result.put("size", 0);
        result.put("content", "");
        return result;
    }

    /** Tail a text file: read at most 512 KB from the end, keep the last N lines. */
    private String tailTextFile(Path path, int lines) {
        try {
            long size = Files.size(path);
            if (size == 0) {
                return "";
            }
            long start = Math.max(size - DEBUG_LOG_MAX_TAIL_BYTES, 0);
            int len = (int) (size - start);
            byte[] data = new byte[len];
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                raf.seek(start);
                raf.readFully(data);
            }
            String text = new String(data, StandardCharsets.UTF_8);
            String[] split = text.split("\\R", -1);
            int from = Math.max(0, split.length - lines);
            return String.join("\n", Arrays.copyOfRange(split, from, split.length));
        } catch (IOException e) {
            log.warn("Failed to read backend debug log file {}", path, e);
            return "";
        }
    }

    // ==== CONSOLE ====
    // NOTE: /console/inbox/events, /console/inbox/read,
    // /console/inbox/events/{event_id}, /console/inbox/traces/{run_id} are
    // implemented in ConsoleController. Duplicate routes are intentionally not
    // declared here to avoid Spring ambiguous-mapping errors.

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
    // === PRECISE METHOD GAPS ===

    @PutMapping("/agents/{agentId}/config/agents/llm-routing")
    public Map<String, String> agentLlmRoutingUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/config/channels")
    public Map<String, String> agentChannelsUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/config/heartbeat")
    public Map<String, String> agentHeartbeatUpdate(@PathVariable String agentId) { return Map.of("status", "ok"); }
    @PutMapping("/agents/{agentId}/config/user-timezone")
    public ResponseEntity<?> agentTimezoneUpdate(@PathVariable String agentId,
                                                  @RequestBody Map<String, Object> body) { return updateUserTimezone(agentId, body); }
    @GetMapping("/agents/{agentId}/config/user-timezone")
    public Map<String, String> agentTimezoneGet(@PathVariable String agentId) {
        return Map.of("timezone", resolveUserTimezone(agentId));
    }

    @PostMapping("/agents/{agentId}/cron/jobs")
    public Map<String, String> agentCronJobCreate(@PathVariable String agentId) { return Map.of("id", UUID.randomUUID().toString()); }
    @PostMapping("/agents/{agentId}/mcp")
    public Map<String, String> agentMcpCreate(@PathVariable String agentId) { return Map.of("status", "ok"); }

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

    private static final Map<String, String> NON_STANDARD_TZ_ALIASES = Map.ofEntries(
        Map.entry("Asia/Beijing", "Asia/Shanghai"),
        Map.entry("Asia/Calcutta", "Asia/Kolkata"),
        Map.entry("Asia/Saigon", "Asia/Ho_Chi_Minh"),
        Map.entry("Asia/Katmandu", "Asia/Kathmandu"),
        Map.entry("Asia/Rangoon", "Asia/Yangon"),
        Map.entry("Asia/Thimbu", "Asia/Thimphu"),
        Map.entry("Asia/Ujung_Pandang", "Asia/Makassar"),
        Map.entry("Asia/Ulan_Bator", "Asia/Ulaanbaatar"),
        Map.entry("Pacific/Samoa", "Pacific/Pago_Pago"),
        Map.entry("Pacific/Ponape", "Pacific/Pohnpei"),
        Map.entry("Pacific/Truk", "Pacific/Chuuk"),
        Map.entry("Atlantic/Faeroe", "Atlantic/Faroe"),
        Map.entry("Europe/Kiev", "Europe/Kyiv"),
        Map.entry("PRC", "Asia/Shanghai")
    );

    private String normalizeTimezone(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String alias = NON_STANDARD_TZ_ALIASES.get(name);
        if (alias != null && isValidZoneId(alias)) {
            return alias;
        }
        return isValidZoneId(name) ? name : null;
    }

    private boolean isValidZoneId(String name) {
        try {
            ZoneId.of(name);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    // ===== ACP config helpers (ported from qwenpaw config.py /config/acp) =====

    private static final Set<String> ALLOWED_ACP_TOOL_PARSE_MODES = Set.of(
        "call_title", "update_detail", "call_detail"
    );

    private Map<String, Object> acpConfigFor(String agentId) {
        Map<String, Object> stored = com.agent.coding.agent.AgentStore.getACPConfig(agentId);
        @SuppressWarnings("unchecked")
        Map<String, Object> agents = stored.get("agents") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        mergeDefaultAcpAgents(agents);
        Map<String, Object> result = new LinkedHashMap<>(stored);
        result.put("agents", agents);
        return result;
    }

    private ResponseEntity<?> saveAcpConfig(String agentId, Map<String, Object> body) {
        Map<String, Object> config = new LinkedHashMap<>(body);
        Object rawAgents = body.get("agents");
        if (rawAgents instanceof Map<?, ?> map) {
            Map<String, Object> agents = new LinkedHashMap<>((Map<String, Object>) map);
            mergeDefaultAcpAgents(agents);
            config.put("agents", agents);
        }
        com.agent.coding.agent.AgentStore.saveACPConfig(agentId, config);
        return ResponseEntity.ok(acpConfigFor(agentId));
    }

    private ResponseEntity<?> acpAgentConfigFor(String agentId, String agentName) {
        Map<String, Object> acp = acpConfigFor(agentId);
        @SuppressWarnings("unchecked")
        Map<String, Object> agents = (Map<String, Object>) acp.get("agents");
        Object agent = agents.get(agentName);
        if (agent == null) {
            return ResponseEntity.status(404).body(Map.of("detail", "ACP agent '" + agentName + "' not found"));
        }
        return ResponseEntity.ok(agent);
    }

    private ResponseEntity<?> saveAcpAgentConfig(String agentId, String agentName,
                                                 Map<String, Object> body) {
        String mode = Objects.toString(body.get("tool_parse_mode"), "");
        if (!ALLOWED_ACP_TOOL_PARSE_MODES.contains(mode)) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "Invalid tool_parse_mode. Allowed values: call_detail, call_title, update_detail"
            ));
        }
        String name = agentName.trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "ACP agent name cannot be empty"));
        }
        Map<String, Object> stored = com.agent.coding.agent.AgentStore.getACPConfig(agentId);
        @SuppressWarnings("unchecked")
        Map<String, Object> agents = stored.get("agents") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        agents.put(name, new LinkedHashMap<>(body));
        stored.put("agents", agents);
        com.agent.coding.agent.AgentStore.saveACPConfig(agentId, stored);
        return ResponseEntity.ok(agents.get(name));
    }

    private void mergeDefaultAcpAgents(Map<String, Object> agents) {
        Map<String, Object> defaults = defaultAcpAgents();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            agents.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, Object> defaultAcpAgents() {
        Map<String, Object> agents = new LinkedHashMap<>();
        agents.put("opencode", acpAgent(true, "opencode", List.of("acp"), "update_detail"));
        agents.put("qwen_code", acpAgent(true, "qwen", List.of("--acp"), "call_detail"));
        agents.put("claude_code", acpAgent(true, "npx", List.of("-y", "@zed-industries/claude-agent-acp"), "update_detail"));
        agents.put("codex", acpAgent(true, "npx", List.of("-y", "@zed-industries/codex-acp"), "call_detail"));
        return agents;
    }

    private Map<String, Object> acpAgent(boolean enabled, String command, List<String> args, String toolParseMode) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("enabled", enabled);
        agent.put("command", command);
        agent.put("args", args);
        agent.put("env", Map.of());
        agent.put("trusted", true);
        agent.put("tool_parse_mode", toolParseMode);
        agent.put("stdio_buffer_limit_bytes", 50 * 1024 * 1024);
        return agent;
    }
}
