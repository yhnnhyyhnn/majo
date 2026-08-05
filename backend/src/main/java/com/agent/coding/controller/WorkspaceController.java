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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);
    private static final Path WORKSPACE = Paths.get(System.getProperty("user.dir"));
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

    @GetMapping("/workspace/language")
    public Map<String, String> language() { return Map.of("language", "en"); }
    @PutMapping("/workspace/language")
    public Map<String, String> languageUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/workspace/running-config")
    public Map<String, Object> runningConfig() { return Map.of(); }
    @PutMapping("/workspace/running-config")
    public Map<String, String> runningConfigUpdate() { return Map.of("status", "ok"); }
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
    public Map<String, Object> agentRunningConfig(@PathVariable String agentId) { return runningConfig(); }
    @GetMapping("/agents/{agentId}/workspace/language")
    public Map<String, String> agentLanguage(@PathVariable String agentId) { return language(); }
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
}
