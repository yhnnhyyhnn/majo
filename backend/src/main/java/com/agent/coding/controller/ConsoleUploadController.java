package com.agent.coding.controller;

import com.agent.coding.skill.SkillStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Chat-attachment upload and preview, mirroring qwenpaw's console upload +
 * /files/preview pair: uploads land in a dedicated media directory and the
 * stored file is streamed back by its path for inline preview.
 */
@RestController
@RequestMapping("/api")
public class ConsoleUploadController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleUploadController.class);

    /** Extension → MIME for attachment preview (image/audio/video/pdf). */
    private static final Map<String, String> MIME_MAP = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"), Map.entry("svg", "image/svg+xml"),
            Map.entry("bmp", "image/bmp"), Map.entry("ico", "image/x-icon"),
            Map.entry("pdf", "application/pdf"), Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"), Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"), Map.entry("m4a", "audio/mp4"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"));

    private static Path uploadsDir() {
        return SkillStore.WORKING_DIR.resolve("uploads");
    }

    @PostMapping("/console/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "file is required"));
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            original = "file";
        }
        // Keep only the base name; strip any client-supplied directory parts.
        original = original.replace('\\', '/');
        if (original.contains("/")) {
            original = original.substring(original.lastIndexOf('/') + 1);
        }
        try {
            Path dir = uploadsDir();
            Files.createDirectories(dir);
            String safeName = original;
            String ext = "";
            int dot = original.lastIndexOf('.');
            if (dot > 0) {
                safeName = original.substring(0, dot);
                ext = original.substring(dot);
            }
            String storedName = UUID.randomUUID().toString().replace("-", "")
                    + "_" + sanitize(safeName) + ext;
            Path target = dir.resolve(storedName).normalize();
            if (!target.startsWith(dir)) {
                return ResponseEntity.badRequest().body(Map.of("detail", "Unsafe filename"));
            }
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", target.toAbsolutePath().normalize().toString());
            result.put("file_name", original);
            result.put("stored_name", storedName);
            result.put("size", Files.size(target));
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.warn("Console upload failed", e);
            return ResponseEntity.internalServerError().body(Map.of("detail", e.getMessage()));
        }
    }

    @GetMapping({"/files/preview/{filepath:.+}", "/files/preview/{filepath:.+}/"})
    public ResponseEntity<?> preview(@PathVariable String filepath) {
        String normalized = filepath.replace('\\', '/');
        Path dir;
        Path target;
        try {
            dir = uploadsDir().toAbsolutePath().normalize();
            target = dir.resolve(normalized).normalize();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid preview path"));
        }
        if (!target.startsWith(dir)) {
            return ResponseEntity.status(403).body(Map.of("error", "Path traversal"));
        }
        if (!Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        String name = target.getFileName().toString();
        String lower = name.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot > 0 ? lower.substring(dot + 1) : "";
        String mime = MIME_MAP.get(ext);
        MediaType mediaType = mime != null ? MediaType.parseMediaType(mime)
                : MediaType.APPLICATION_OCTET_STREAM;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        headers.setContentType(mediaType);
        return ResponseEntity.ok().headers(headers)
                .body(new FileSystemResource(target));
    }

    private static String sanitize(String name) {
        Set<Character> allowedExtra = Set.of('-', '_', '.', ' ');
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c) || allowedExtra.contains(c)) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "file" : sb.toString();
    }
}
