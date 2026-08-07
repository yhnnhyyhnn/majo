package com.agent.coding.controller;

import com.agent.coding.SettingsService;
import com.agent.coding.repository.ProviderRepository;
import com.agent.coding.repository.ModelConfigRepository;
import com.agent.coding.service.PluginRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);
    private static final Path WORKSPACE = com.agent.coding.skill.SkillStore.WORKING_DIR;
    private static final Set<String> SKIP_NAMES = Set.of(
        ".git", "__pycache__", ".venv", "node_modules", ".mypy_cache",
        ".pytest_cache", ".ruff_cache", ".hypothesis", "target", ".idea"
    );
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final SettingsService settingsService;
    private final ProviderRepository providerRepo;
    private final ModelConfigRepository modelConfigRepo;
    private final PluginRegistry pluginRegistry;

    public WorkspaceController(SettingsService settingsService,
                                ProviderRepository providerRepo,
                                ModelConfigRepository modelConfigRepo,
                                PluginRegistry pluginRegistry) {
        this.settingsService = settingsService;
        this.providerRepo = providerRepo;
        this.modelConfigRepo = modelConfigRepo;
        this.pluginRegistry = pluginRegistry;
    }

    private boolean isSkipped(String name) {
        return name.startsWith(".") || SKIP_NAMES.contains(name);
    }

    // === File Tree ===

    @GetMapping("/workspace/code-files")
    public List<Map<String, Object>> listCodeFiles() {
        return listCodeFiles(WORKSPACE);
    }

    public List<Map<String, Object>> listCodeFiles(Path workspace) {
        List<Map<String, Object>> files = new ArrayList<>();
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (workspace.equals(dir)) return FileVisitResult.CONTINUE;
                    return isSkipped(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (isSkipped(name)) return FileVisitResult.CONTINUE;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    String rel = workspace.relativize(file).toString().replace("\\", "/");
                    entry.put("filename", rel);
                    entry.put("path", rel);
                    entry.put("size", attrs.size());
                    entry.put("modified_time", ISO.format(attrs.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault())));
                    files.add(entry);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list workspace files", e);
        }
        return files;
    }

    @GetMapping("/workspace/code-files/**")
    public ResponseEntity<?> readCodeFile(HttpServletRequest req) {
        return readCodeFile(WORKSPACE, req);
    }

    public ResponseEntity<?> readCodeFile(Path workspace, HttpServletRequest req) {
        String filePath = req.getRequestURI().replace("/api/workspace/code-files/", "");
        if (req.getRequestURI().contains("/agents/")) {
            filePath = req.getRequestURI().replaceAll(".*/agents/[^/]+/workspace/code-files/", "");
        }
        Path target = workspace.resolve(filePath).normalize();
        if (!target.startsWith(workspace)) return ResponseEntity.badRequest().body(Map.of("error", "Path traversal"));
        if (!Files.exists(target)) return ResponseEntity.notFound().build();
        if (Files.isDirectory(target)) {
            List<Map<String, Object>> list = new ArrayList<>();
            try {
                Files.list(target).filter(f -> !isSkipped(f.getFileName().toString())).forEach(f -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    String rel = workspace.relativize(f).toString().replace("\\", "/");
                    e.put("filename", rel); e.put("path", rel);
                    e.put("size", f.toFile().length());
                    e.put("modified_time", ISO.format(Instant.ofEpochMilli(f.toFile().lastModified()).atZone(ZoneId.systemDefault())));
                    e.put("is_dir", Files.isDirectory(f));
                    list.add(e);
                });
            } catch (IOException ignored) {}
            return ResponseEntity.ok(list);
        }
        if (target.toFile().length() > 5 * 1024 * 1024)
            return ResponseEntity.status(413).body(Map.of("error", "File too large (>5MB)"));
        try {
            String content = Files.readString(target);
            return ResponseEntity.ok(Map.of("content", content));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/workspace/code-files/**")
    public ResponseEntity<?> writeCodeFile(HttpServletRequest req, @RequestBody Map<String, String> body) {
        return writeCodeFile(WORKSPACE, req, body);
    }

    public ResponseEntity<?> writeCodeFile(Path workspace, HttpServletRequest req, Map<String, String> body) {
        String filePath = req.getRequestURI().replace("/api/workspace/code-files/", "");
        if (req.getRequestURI().contains("/agents/")) {
            filePath = req.getRequestURI().replaceAll(".*/agents/[^/]+/workspace/code-files/", "");
        }
        Path target = workspace.resolve(filePath).normalize();
        if (!target.startsWith(workspace)) return ResponseEntity.badRequest().body(Map.of("error", "Path traversal"));
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, body.getOrDefault("content", ""));
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // === Markdown Files ===

    @GetMapping("/workspace/files")
    public List<Map<String, Object>> listMdFiles() {
        return listMdFiles(WORKSPACE);
    }

    public List<Map<String, Object>> listMdFiles(Path workspace) {
        List<Map<String, Object>> files = new ArrayList<>();
        try (var stream = Files.list(workspace)) {
            stream.filter(f -> f.getFileName().toString().endsWith(".md") && !isSkipped(f.getFileName().toString()))
                .forEach(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("filename", f.getFileName().toString());
                    m.put("path", f.getFileName().toString());
                    m.put("size", f.toFile().length());
                    m.put("modified_time", ISO.format(Instant.ofEpochMilli(f.toFile().lastModified()).atZone(ZoneId.systemDefault())));
                    files.add(m);
                });
        } catch (IOException ignored) {}
        return files;
    }

    @GetMapping("/workspace/files/{name}")
    public ResponseEntity<?> readMdFile(@PathVariable String name) {
        return readMdFile(WORKSPACE, name);
    }

    public ResponseEntity<?> readMdFile(Path workspace, String name) {
        Path target = workspace.resolve(name).normalize();
        if (!target.startsWith(workspace)) return ResponseEntity.badRequest().body(Map.of("error", "Invalid path"));
        if (!Files.exists(target)) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok(Map.of("content", Files.readString(target)));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/workspace/files/{name}")
    public ResponseEntity<?> writeMdFile(@PathVariable String name, @RequestBody Map<String, String> body) {
        return writeMdFile(WORKSPACE, name, body);
    }

    public ResponseEntity<?> writeMdFile(Path workspace, String name, Map<String, String> body) {
        Path target = workspace.resolve(name).normalize();
        if (!target.startsWith(workspace)) return ResponseEntity.badRequest().body(Map.of("error", "Invalid path"));
        try {
            Files.writeString(target, body.getOrDefault("content", ""));
            return ResponseEntity.ok(Map.of("written", true));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // === Download/Upload ===

    @GetMapping("/workspace/download")
    public ResponseEntity<Resource> downloadWorkspace() {
        return downloadWorkspace(WORKSPACE);
    }

    public ResponseEntity<Resource> downloadWorkspace(Path workspace) {
        // Return a simple zip stub — full implementation would zip the workspace
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Content-Disposition", "attachment; filename=workspace.zip")
            .body(new FileSystemResource(workspace.resolve("pom.xml")));
    }

    @PostMapping("/workspace/upload")
    public Map<String, String> uploadWorkspace() {
        return Map.of("url", "");
    }

    // === Coding Project ===

    @GetMapping("/workspace/coding-project/list")
    public List<Map<String, String>> codingProjectList() { return List.of(); }
    @GetMapping("/workspace/coding-project")
    public Map<String, Object> codingProject() { return Map.of(); }
    @GetMapping("/workspace/coding-project/browse-dirs")
    public List<Map<String, String>> browseDirs() { return List.of(); }
    @PostMapping("/workspace/coding-project/create")
    public Map<String, String> codingProjectCreate() { return Map.of("id", UUID.randomUUID().toString()); }
    @PostMapping("/workspace/coding-project/clone")
    public Map<String, String> codingProjectClone() { return Map.of("id", UUID.randomUUID().toString()); }
    @PostMapping("/workspace/coding-project/import-local")
    public Map<String, String> codingProjectImport() { return Map.of("id", UUID.randomUUID().toString()); }
    @PostMapping("/workspace/coding-project/upload-zip")
    public Map<String, String> codingProjectUploadZip() { return Map.of("id", UUID.randomUUID().toString()); }

    // === Audio / Transcription ===

    @GetMapping("/workspace/local-whisper-status")
    public Map<String, Object> localWhisperStatus() {
        return Map.of("whisper_installed", false);
    }
    @PostMapping("/workspace/transcribe")
    public Map<String, String> transcribe() { return Map.of("text", ""); }
    @GetMapping("/workspace/transcription-provider")
    public Map<String, String> getConfiguredTranscriptionProvider() {
        return Map.of("provider_id", settingsService.getTranscriptionProviderId());
    }
    @PutMapping("/workspace/transcription-provider")
    public ResponseEntity<?> putTranscriptionProvider(@RequestBody Map<String, Object> body) {
        String id = Objects.toString(body.get("provider_id"), "").trim();
        settingsService.setTranscriptionProviderId(id);
        return ResponseEntity.ok(Map.of("provider_id", id));
    }
    @GetMapping("/workspace/transcription-providers")
    public Map<String, Object> getTranscriptionProviders() {
        var providers = new ArrayList<Map<String, Object>>();
        // qwenpaw: isinstance(provider, OpenAIProvider) && (key || !require_key)
        for (var p : providerRepo.findAll()) {
            if (!"OpenAIChatModel".equalsIgnoreCase(p.getChatModel())) continue;
            if (p.getBaseUrl() == null || p.getBaseUrl().isBlank()) continue;
            if (p.getRequireApiKey() != null && p.getRequireApiKey()
                    && (p.getApiKey() == null || p.getApiKey().isBlank())) continue;
            providers.add(Map.of("id", p.getId(), "name", p.getName(), "available", true));
        }
        for (var e : modelConfigRepo.findAll()) {
            if (e.getBaseUrl() != null && !e.getBaseUrl().isBlank()) {
                providers.add(Map.of("id", e.getId().toString(), "name", e.getName(), "available", true));
            }
        }
        return Map.of("providers", providers,
            "configured_provider_id", settingsService.getTranscriptionProviderId());
    }
    @GetMapping("/workspace/transcription-provider-type")
    public Map<String, String> getTranscriptionProviderType() {
        return Map.of("transcription_provider_type", settingsService.getTranscriptionProviderType());
    }
    @PutMapping("/workspace/transcription-provider-type")
    public ResponseEntity<?> putTranscriptionProviderType(@RequestBody Map<String, Object> body) {
        String type = Objects.toString(body.get("transcription_provider_type"), "").trim().toLowerCase();
        if (!Set.of("disabled", "whisper_api", "local_whisper").contains(type)) {
            return ResponseEntity.badRequest()
                .body(Map.of("detail", "Invalid type '" + type + "'. Must be one of: disabled, whisper_api, local_whisper"));
        }
        settingsService.setTranscriptionProviderType(type);
        return ResponseEntity.ok(Map.of("transcription_provider_type", type));
    }

    // === Config ===

    private static final Set<String> SUPPORTED_AGENT_LANGUAGES = Set.of(
        "zh", "en", "id", "ru", "local", "qa"
    );

    private String resolveAgentId(HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getParameter("agent");
        }
        return (agentId == null || agentId.isBlank()) ? "default" : agentId;
    }

    @GetMapping("/workspace/language")
    public Map<String, String> language(HttpServletRequest request) {
        return language(resolveAgentId(request));
    }

    public Map<String, String> language(String agentId) {
        Map<String, Object> profile = com.agent.coding.agent.AgentStore.getProfile(agentId);
        if (profile == null) {
            throw new com.agent.coding.skill.SkillNotFoundException("Agent '" + agentId + "' not found");
        }
        String lang = com.agent.coding.skill.SkillService.str(profile.get("language"), "zh");
        Map<String, String> result = new LinkedHashMap<>();
        result.put("language", lang);
        result.put("agent_id", agentId);
        return result;
    }

    @PutMapping("/workspace/language")
    public ResponseEntity<?> languageUpdate(@RequestBody Map<String, Object> body,
                                            HttpServletRequest request) {
        return languageUpdate(resolveAgentId(request), body);
    }

    public ResponseEntity<?> languageUpdate(String agentId, Map<String, Object> body) {
        String language = Objects.toString(body.get("language"), "").trim().toLowerCase();
        if (!SUPPORTED_AGENT_LANGUAGES.contains(language)) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "Invalid language '" + language + "'. Must be one of: "
                    + String.join(", ", SUPPORTED_AGENT_LANGUAGES.stream().sorted().toList())
            ));
        }
        Map<String, Object> profile = com.agent.coding.agent.AgentStore.getProfile(agentId);
        if (profile == null) {
            throw new com.agent.coding.skill.SkillNotFoundException("Agent '" + agentId + "' not found");
        }
        String oldLanguage = com.agent.coding.skill.SkillService.str(profile.get("language"), "zh");
        com.agent.coding.agent.AgentStore.updateAgent(agentId, Map.of("language", language));
        List<String> copiedFiles = new ArrayList<>();
        if (!oldLanguage.equals(language)) {
            copiedFiles = copyWorkspaceMdFiles(language, agentId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("language", language);
        result.put("copied_files", copiedFiles);
        result.put("agent_id", agentId);
        return ResponseEntity.ok(result);
    }

    private List<String> copyWorkspaceMdFiles(String language, String agentId) {
        // qwenpaw copies bundled MD templates for the new language into the
        // workspace. majo ships no per-language MD templates, so nothing to copy.
        return new ArrayList<>();
    }

    @GetMapping("/workspace/running-config")
    public Map<String, Object> runningConfig(HttpServletRequest request) {
        return runningConfig(resolveAgentId(request));
    }

    public Map<String, Object> runningConfig(String agentId) {
        Map<String, Object> profile = com.agent.coding.agent.AgentStore.getProfile(agentId);
        if (profile == null) {
            throw new com.agent.coding.skill.SkillNotFoundException("Agent '" + agentId + "' not found");
        }
        Map<String, Object> defaults = com.agent.coding.agent.RunningConfigDefaults.defaultConfig();
        Map<String, Object> stored = com.agent.coding.agent.AgentStore.getRunningConfig(agentId);
        Map<String, Object> merged = com.agent.coding.agent.RunningConfigDefaults.deepMerge(defaults, stored);
        merged.put("approval_level", com.agent.coding.agent.AgentStore.getApprovalLevel(agentId));
        return merged;
    }

    @PutMapping("/workspace/running-config")
    public Map<String, Object> runningConfigUpdate(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        return runningConfigUpdate(resolveAgentId(request), body);
    }

    public Map<String, Object> runningConfigUpdate(String agentId, Map<String, Object> body) {
        Map<String, Object> profile = com.agent.coding.agent.AgentStore.getProfile(agentId);
        if (profile == null) {
            throw new com.agent.coding.skill.SkillNotFoundException("Agent '" + agentId + "' not found");
        }
        Object rawLevel = body.get("approval_level");
        String approvalLevel = rawLevel == null ? null : Objects.toString(rawLevel).trim().toUpperCase();
        Map<String, Object> running = new LinkedHashMap<>(body);
        running.remove("approval_level");
        com.agent.coding.agent.AgentStore.saveRunningConfig(agentId, running, approvalLevel);
        Map<String, Object> merged = com.agent.coding.agent.RunningConfigDefaults.deepMerge(
            com.agent.coding.agent.RunningConfigDefaults.defaultConfig(), running);
        merged.put("approval_level", com.agent.coding.agent.AgentStore.getApprovalLevel(agentId));
        return merged;
    }
    @GetMapping("/workspace/system-prompt-files")
    public List<Map<String, String>> systemPromptFiles() { return List.of(); }
    @PutMapping("/workspace/system-prompt-files")
    public Map<String, String> systemPromptFilesUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/workspace/commands/available")
    public List<Map<String, String>> commands() { return List.of(); }
    @GetMapping("/workspace/memory")
    public List<Map<String, String>> memory() { return List.of(); }
    @GetMapping("/workspace/memory/{path}")
    public Map<String, String> memoryFile(@PathVariable String path) { return Map.of("content", ""); }
    @PutMapping("/workspace/memory/{path}")
    public Map<String, String> memoryFileSave(@PathVariable String path) { return Map.of("status", "ok"); }
    @GetMapping("/workspace/watch")
    public Map<String, String> watch() { return Map.of("status", "ok"); }
    @GetMapping("/workspace/binary-files/{path}")
    public Map<String, String> binaryFile(@PathVariable String path) { return Map.of("url", ""); }

    // === Agent-scoped workspace (resolves per-agent workspace_dir) ===

    @GetMapping("/agents/{agentId}/workspace/code-files")
    public List<Map<String, Object>> agentCodeFiles(@PathVariable String agentId) {
        return listCodeFiles(workspaceFor(agentId));
    }
    @GetMapping("/agents/{agentId}/workspace/code-files/**")
    public ResponseEntity<?> agentCodeFile(@PathVariable String agentId, HttpServletRequest req) {
        return readCodeFile(workspaceFor(agentId), req);
    }
    @PutMapping("/agents/{agentId}/workspace/code-files/**")
    public ResponseEntity<?> agentCodeFileSave(@PathVariable String agentId, HttpServletRequest req, @RequestBody Map<String, String> body) {
        return writeCodeFile(workspaceFor(agentId), req, body);
    }
    @GetMapping("/agents/{agentId}/workspace/files")
    public List<Map<String, Object>> agentFiles(@PathVariable String agentId) {
        return listMdFiles(workspaceFor(agentId));
    }
    @GetMapping("/agents/{agentId}/workspace/files/{name}")
    public ResponseEntity<?> agentMdFile(@PathVariable String agentId, @PathVariable String name) {
        return readMdFile(workspaceFor(agentId), name);
    }
    @PutMapping("/agents/{agentId}/workspace/files/{name}")
    public ResponseEntity<?> agentMdFileSave(@PathVariable String agentId, @PathVariable String name, @RequestBody Map<String, String> body) {
        return writeMdFile(workspaceFor(agentId), name, body);
    }
    @GetMapping("/agents/{agentId}/workspace/running-config")
    public Map<String, Object> agentRunningConfig(@PathVariable String agentId) { return runningConfig(agentId); }
    @PutMapping("/agents/{agentId}/workspace/running-config")
    public Map<String, Object> agentRunningConfigSave(@PathVariable String agentId,
                                                       @RequestBody Map<String, Object> body) { return runningConfigUpdate(agentId, body); }
    @GetMapping("/agents/{agentId}/workspace/language")
    public Map<String, String> agentLanguage(@PathVariable String agentId) { return language(agentId); }
    @PutMapping("/agents/{agentId}/workspace/language")
    public ResponseEntity<?> agentLanguageSave(@PathVariable String agentId,
                                                @RequestBody Map<String, Object> body) { return languageUpdate(agentId, body); }
    @GetMapping("/agents/{agentId}/workspace/system-prompt-files")
    public List<Map<String, String>> agentSysPromptFiles(@PathVariable String agentId) { return systemPromptFiles(); }
    @GetMapping("/agents/{agentId}/workspace/commands/available")
    public List<Map<String, String>> agentCommands(@PathVariable String agentId) { return commands(); }
    @GetMapping("/agents/{agentId}/workspace/memory")
    public List<Map<String, String>> agentMemory(@PathVariable String agentId) { return memory(); }
    @GetMapping("/agents/{agentId}/workspace/download")
    public ResponseEntity<Resource> agentDownload(@PathVariable String agentId) { return downloadWorkspace(workspaceFor(agentId)); }
    @GetMapping("/agents/{agentId}/workspace/watch")
    public Map<String, String> agentWatch(@PathVariable String agentId) { return watch(); }

    private Path workspaceFor(String agentId) {
        return com.agent.coding.skill.SkillRegistry.workspaceDirForAgent(agentId);
    }

    // ── Audio mode (persisted via SettingsService) ────────────────────
    @GetMapping("/workspace/audio-mode")
    public Map<String, String> getAudioMode() {
        return Map.of("audio_mode", settingsService.getAudioMode());
    }

    @PutMapping("/workspace/audio-mode")
    public ResponseEntity<?> putAudioMode(@RequestBody Map<String, Object> body) {
        String mode = Objects.toString(body.get("audio_mode"), "").trim().toLowerCase();
        if (!Set.of("auto", "native").contains(mode)) {
            return ResponseEntity.badRequest()
                .body(Map.of("detail", "Invalid audio_mode '" + mode + "'. Must be one of: auto, native"));
        }
        settingsService.setAudioMode(mode);
        return ResponseEntity.ok(Map.of("audio_mode", settingsService.getAudioMode()));
    }

    // ── Channels config (qwenpaw: config.py /channels) ────────────────
    private static final List<String> BUILTIN_CHANNEL_KEYS = List.of(
        "imessage", "discord", "dingtalk", "feishu", "qq", "telegram",
        "mattermost", "mqtt", "console", "matrix", "slack", "voice",
        "sip", "wecom", "xiaoyi", "yuanbao", "wechat", "onebot"
    );

    @GetMapping("/config/channels/types")
    public List<String> channelTypes() {
        var all = new ArrayList<>(BUILTIN_CHANNEL_KEYS);
        all.addAll(pluginRegistry.getRegisteredChannels().keySet());
        return all;
    }

    @GetMapping("/config/channels")
    public Map<String, Object> listChannels() {
        var result = new LinkedHashMap<String, Object>();
        for (String key : BUILTIN_CHANNEL_KEYS) {
            result.put(key, Map.of("enabled", "console".equals(key), "bot_prefix", "", "isBuiltin", true));
        }
        for (var entry : pluginRegistry.getRegisteredChannels().entrySet()) {
            result.put(entry.getKey(), Map.of("enabled", false, "bot_prefix", "", "isBuiltin", false));
        }
        return result;
    }

    @GetMapping("/config/channels/schemas")
    public Map<String, Map<String, Object>> channelSchemas() {
        return pluginRegistry.getRegisteredChannels();
    }

    @PutMapping("/config/channels")
    public Map<String, Object> updateChannels(@RequestBody Map<String, Object> body) {
        return body;
    }

    @GetMapping("/config/channels/{channelName}")
    public Map<String, Object> getChannel(@PathVariable String channelName) {
        return Map.of("enabled", true, "bot_prefix", "", "isBuiltin", true);
    }

    @PutMapping("/config/channels/{channelName}")
    public Map<String, Object> updateChannel(@PathVariable String channelName,
                                              @RequestBody Map<String, Object> body) {
        return body;
    }

    @GetMapping("/config/channels/{channel}/qrcode")
    public ResponseEntity<?> channelQrcode(@PathVariable String channel) {
        return ResponseEntity.status(404).body(Map.of("detail", "QR code not supported for channel: " + channel));
    }

    @GetMapping("/config/channels/{channel}/qrcode/status")
    public ResponseEntity<?> channelQrcodeStatus(@PathVariable String channel, @RequestParam String token) {
        return ResponseEntity.status(404).body(Map.of("detail", "QR code not supported for channel: " + channel));
    }

    @GetMapping("/config/channels/{channelName}/health")
    public Map<String, Object> channelHealth(@PathVariable String channelName) {
        return Map.of("channel", channelName, "status", "healthy", "detail", "");
    }

    @PostMapping("/config/channels/{channelName}/restart")
    public Map<String, Object> channelRestart(@PathVariable String channelName) {
        return Map.of("channel", channelName, "status", "restarted", "detail", "");
    }

    // ── Heartbeat config (qwenpaw: config.py /heartbeat) ──────────────
    @GetMapping("/config/heartbeat")
    public Map<String, Object> getHeartbeat() {
        return Map.of(
            "enabled", settingsService.isHeartbeatEnabled(),
            "every", settingsService.getHeartbeatEvery(),
            "target", settingsService.getHeartbeatTarget(),
            "timeoutSeconds", settingsService.getHeartbeatTimeoutSeconds()
        );
    }

    @PutMapping("/config/heartbeat")
    public Map<String, Object> putHeartbeat(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String every = Objects.toString(body.get("every"), "6h");
        String target = Objects.toString(body.get("target"), "main");
        int timeout = ((Number) body.getOrDefault("timeoutSeconds", 120)).intValue();
        settingsService.setHeartbeatConfig(enabled, every, target, timeout);
        return getHeartbeat();
    }

    @PostMapping("/config/heartbeat/run")
    public Map<String, Object> runHeartbeat() {
        return Map.of("started", true);
    }

    // ── User timezone (per-agent, ported from qwenpaw config.py /user-timezone) ──
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

    @GetMapping("/config/user-timezone")
    public Map<String, String> configUserTimezone(HttpServletRequest request) {
        return Map.of("timezone", resolveUserTimezone(resolveAgentId(request)));
    }

    @PutMapping("/config/user-timezone")
    public ResponseEntity<?> configUserTimezoneUpdate(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        return updateUserTimezone(resolveAgentId(request), body);
    }

    @GetMapping("/agents/{agentId}/config/user-timezone")
    public Map<String, String> agentTimezoneGet(@PathVariable String agentId) {
        return Map.of("timezone", resolveUserTimezone(agentId));
    }

    @PutMapping("/agents/{agentId}/config/user-timezone")
    public ResponseEntity<?> agentTimezoneUpdate(@PathVariable String agentId,
                                                 @RequestBody Map<String, Object> body) {
        return updateUserTimezone(agentId, body);
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

    // ── Agent-scoped channels config (port of qwenpaw agent_scoped /agents/{agentId}/config/channels) ──
    @GetMapping("/agents/{agentId}/config/channels/{channel_name}")
    public Object agentChannelDetail(@PathVariable String agentId, @PathVariable String channel_name) {
        return getChannel(channel_name);
    }
    @PutMapping("/agents/{agentId}/config/channels/{channel_name}")
    public Object agentChannelUpdate(@PathVariable String agentId, @PathVariable String channel_name,
                                     @RequestBody Map<String, Object> body) {
        return updateChannel(channel_name, body);
    }
    @GetMapping("/agents/{agentId}/config/channels/{channel_name}/health")
    public Object agentChannelHealth(@PathVariable String agentId, @PathVariable String channel_name) {
        return channelHealth(channel_name);
    }
    @PostMapping("/agents/{agentId}/config/channels/{channel_name}/restart")
    public Object agentChannelRestart(@PathVariable String agentId, @PathVariable String channel_name) {
        return channelRestart(channel_name);
    }
    @GetMapping("/agents/{agentId}/config/channels/{channel}/qrcode")
    public Object agentChannelQrcode(@PathVariable String agentId, @PathVariable String channel) {
        return channelQrcode(channel);
    }
    @GetMapping("/agents/{agentId}/config/channels/{channel}/qrcode/status")
    public Object agentChannelQrcodeStatus(@PathVariable String agentId, @PathVariable String channel,
                                           @RequestParam String token) {
        return channelQrcodeStatus(channel, token);
    }
    @GetMapping("/agents/{agentId}/config/channels/schemas")
    public Object agentChannelSchemas(@PathVariable String agentId) { return channelSchemas(); }
    @GetMapping("/agents/{agentId}/config/channels/types")
    public Object agentChannelTypes(@PathVariable String agentId) { return channelTypes(); }
    @PutMapping("/agents/{agentId}/config/channels")
    public Object agentChannelsUpdate(@PathVariable String agentId,
                                      @RequestBody Map<String, Object> body) { return updateChannels(body); }

    // ── Agent-scoped heartbeat (port of qwenpaw agent_scoped /agents/{agentId}/config/heartbeat) ──
    @PostMapping("/agents/{agentId}/config/heartbeat/run")
    public Object agentHeartbeatRun(@PathVariable String agentId) { return runHeartbeat(); }
    @PutMapping("/agents/{agentId}/config/heartbeat")
    public Object agentHeartbeatUpdate(@PathVariable String agentId,
                                       @RequestBody Map<String, Object> body) { return putHeartbeat(body); }
}
