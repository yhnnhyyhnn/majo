package com.agent.coding.controller;

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

    private boolean isSkipped(String name) {
        return name.startsWith(".") || SKIP_NAMES.contains(name);
    }

    // === File Tree ===

    @GetMapping("/workspace/code-files")
    public List<Map<String, Object>> listCodeFiles() {
        List<Map<String, Object>> files = new ArrayList<>();
        try {
            Files.walkFileTree(WORKSPACE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (WORKSPACE.equals(dir)) return FileVisitResult.CONTINUE;
                    return isSkipped(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (isSkipped(name)) return FileVisitResult.CONTINUE;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    String rel = WORKSPACE.relativize(file).toString().replace("\\", "/");
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
        String filePath = req.getRequestURI().replace("/api/workspace/code-files/", "");
        Path target = WORKSPACE.resolve(filePath).normalize();
        if (!target.startsWith(WORKSPACE)) return ResponseEntity.badRequest().body(Map.of("error", "Path traversal"));
        if (!Files.exists(target)) return ResponseEntity.notFound().build();
        if (Files.isDirectory(target)) {
            List<Map<String, Object>> list = new ArrayList<>();
            try {
                Files.list(target).filter(f -> !isSkipped(f.getFileName().toString())).forEach(f -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    String rel = WORKSPACE.relativize(f).toString().replace("\\", "/");
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
        String filePath = req.getRequestURI().replace("/api/workspace/code-files/", "");
        Path target = WORKSPACE.resolve(filePath).normalize();
        if (!target.startsWith(WORKSPACE)) return ResponseEntity.badRequest().body(Map.of("error", "Path traversal"));
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
        List<Map<String, Object>> files = new ArrayList<>();
        try (var stream = Files.list(WORKSPACE)) {
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
        Path target = WORKSPACE.resolve(name).normalize();
        if (!target.startsWith(WORKSPACE)) return ResponseEntity.badRequest().body(Map.of("error", "Invalid path"));
        if (!Files.exists(target)) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok(Map.of("content", Files.readString(target)));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/workspace/files/{name}")
    public ResponseEntity<?> writeMdFile(@PathVariable String name, @RequestBody Map<String, String> body) {
        Path target = WORKSPACE.resolve(name).normalize();
        if (!target.startsWith(WORKSPACE)) return ResponseEntity.badRequest().body(Map.of("error", "Invalid path"));
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
        // Return a simple zip stub — full implementation would zip the workspace
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Content-Disposition", "attachment; filename=workspace.zip")
            .body(new FileSystemResource(WORKSPACE.resolve("pom.xml")));
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

    @GetMapping("/workspace/audio-mode")
    public Map<String, String> audioMode() { return Map.of("enabled", "false"); }
    @PutMapping("/workspace/audio-mode")
    public Map<String, String> audioModeUpdate() { return Map.of("status", "ok"); }
    @GetMapping("/workspace/local-whisper-status")
    public Map<String, String> whisperStatus() { return Map.of("available", "false"); }
    @PostMapping("/workspace/transcribe")
    public Map<String, String> transcribe() { return Map.of("text", ""); }
    @GetMapping("/workspace/transcription-provider")
    public Map<String, String> transcriptionProvider() { return Map.of("provider", "none"); }
    @PutMapping("/workspace/transcription-provider")
    public Map<String, String> transcriptionProviderSet() { return Map.of("status", "ok"); }
    @GetMapping("/workspace/transcription-providers")
    public List<Map<String, String>> transcriptionProviders() { return List.of(); }
    @GetMapping("/workspace/transcription-provider-type")
    public Map<String, String> transcriptionProviderType() { return Map.of("type", "none"); }
    @PutMapping("/workspace/transcription-provider-type")
    public Map<String, String> transcriptionProviderTypeSet() { return Map.of("status", "ok"); }

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

    // === Agent-scoped workspace (delegates to same logic) ===

    @GetMapping("/agents/{agentId}/workspace/code-files")
    public List<Map<String, Object>> agentCodeFiles(@PathVariable String agentId) { return listCodeFiles(); }
    @GetMapping("/agents/{agentId}/workspace/code-files/**")
    public ResponseEntity<?> agentCodeFile(@PathVariable String agentId, HttpServletRequest req) { return readCodeFile(req); }
    @PutMapping("/agents/{agentId}/workspace/code-files/**")
    public ResponseEntity<?> agentCodeFileSave(@PathVariable String agentId, HttpServletRequest req, @RequestBody Map<String, String> body) { return writeCodeFile(req, body); }
    @GetMapping("/agents/{agentId}/workspace/files")
    public List<Map<String, Object>> agentFiles(@PathVariable String agentId) { return listMdFiles(); }
    @GetMapping("/agents/{agentId}/workspace/files/{name}")
    public ResponseEntity<?> agentMdFile(@PathVariable String agentId, @PathVariable String name) { return readMdFile(name); }
    @PutMapping("/agents/{agentId}/workspace/files/{name}")
    public ResponseEntity<?> agentMdFileSave(@PathVariable String agentId, @PathVariable String name, @RequestBody Map<String, String> body) { return writeMdFile(name, body); }
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
    public ResponseEntity<Resource> agentDownload(@PathVariable String agentId) { return downloadWorkspace(); }
    @GetMapping("/agents/{agentId}/workspace/watch")
    public Map<String, String> agentWatch(@PathVariable String agentId) { return watch(); }
}
