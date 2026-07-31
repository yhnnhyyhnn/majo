package com.agent.coding.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class PluginsController {

    private static final Logger log = LoggerFactory.getLogger(PluginsController.class);
    private static final Path PLUGINS_DIR = Paths.get(System.getProperty("user.dir"), "plugins");
    private static final ObjectMapper mapper = new ObjectMapper();

    static { try { Files.createDirectories(PLUGINS_DIR); } catch (IOException ignored) {} }

    @GetMapping("/plugins")
    public List<Map<String, Object>> listPlugins() {
        List<Map<String, Object>> result = new ArrayList<>();
        File[] items = PLUGINS_DIR.toFile().listFiles(File::isDirectory);
        if (items == null) return result;
        for (File item : items) {
            File mf = new File(item, "plugin.json");
            if (!mf.exists()) continue;
            try {
                Map<String, Object> m = mapper.readValue(mf, Map.class);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", m.getOrDefault("id", item.getName()));
                info.put("name", m.getOrDefault("name", item.getName()));
                info.put("version", m.getOrDefault("version", "0.0.0"));
                info.put("description", m.getOrDefault("description", ""));
                info.put("author", m.getOrDefault("author", ""));
                info.put("enabled", true);
                info.put("loaded", true);
                info.put("plugin_type", m.getOrDefault("plugin_type", "unknown"));
                Object entry = m.get("entry");
                info.put("frontend_entry", entry instanceof Map<?,?> em ? em.get("frontend") : null);
                result.add(info);
            } catch (Exception e) { log.warn("Skip broken plugin {}: {}", item.getName(), e.getMessage()); }
        }
        return result;
    }

    @PostMapping("/plugins/install")
    public ResponseEntity<?> installPlugin(@RequestBody Map<String, Object> body) {
        Object src = body.get("source");
        if (src == null || src.toString().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "source is required"));
        }
        String source = src.toString().trim();

        try {
            Path targetDir;
            if (source.startsWith("http://") || source.startsWith("https://")) {
                targetDir = downloadAndExtract(source);
            } else {
                Path srcPath = Paths.get(source).toAbsolutePath().normalize();
                if (!Files.exists(srcPath)) {
                    return ResponseEntity.badRequest().body(Map.of("detail", "Path not found: " + source));
                }
                targetDir = Files.isDirectory(srcPath) ? copyDir(srcPath) : extractZip(srcPath);
            }

            File mf = new File(targetDir.toFile(), "plugin.json");
            if (mf.exists()) {
                Map<String, Object> manifest = mapper.readValue(mf, Map.class);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", manifest.getOrDefault("id", targetDir.getFileName().toString()));
                info.put("name", manifest.getOrDefault("name", ""));
                info.put("version", manifest.getOrDefault("version", "0.0.0"));
                info.put("status", "installed");
                return ResponseEntity.ok(info);
            }
            return ResponseEntity.ok(Map.of("status", "installed", "id", targetDir.getFileName().toString()));
        } catch (Exception e) {
            log.error("Plugin install failed", e);
            return ResponseEntity.status(500).body(Map.of("detail", "Install failed: " + e.getMessage()));
        }
    }

    @PostMapping("/plugins/upload")
    public ResponseEntity<?> uploadPlugin() {
        return ResponseEntity.status(400).body(Map.of("detail", "Upload not supported, use install with source path"));
    }

    private Path downloadAndExtract(String url) throws Exception {
        Path tmp = Files.createTempFile("plugin", ".zip");
        try {
            var conn = (java.net.HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setRequestProperty("Accept", "application/zip, application/octet-stream, */*");
            conn.setRequestProperty("User-Agent", "Majo/1.0");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 400) {
                String msg = "Download failed: HTTP " + code;
                try (var es = conn.getErrorStream()) {
                    if (es != null) msg += " - " + new String(es.readAllBytes());
                }
                throw new IOException(msg);
            }
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return extractZip(tmp);
    }

    private Path copyDir(Path src) throws IOException {
        Path dest = PLUGINS_DIR.resolve(src.getFileName().toString());
        Files.walk(src).forEach(s -> {
            try {
                Path d = dest.resolve(src.relativize(s));
                if (Files.isDirectory(s)) Files.createDirectories(d);
                else Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        });
        return dest;
    }

    private Path extractZip(Path zip) throws Exception {
        String topDir = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip.toFile()))) {
            var entry = zis.getNextEntry();
            while (entry != null) {
                String name = entry.getName();
                if (topDir == null && name.contains("/")) topDir = name.substring(0, name.indexOf('/'));
                Path out = PLUGINS_DIR.resolve(name);
                if (entry.isDirectory()) Files.createDirectories(out);
                else { Files.createDirectories(out.getParent()); Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING); }
                entry = zis.getNextEntry();
            }
        }
        Files.deleteIfExists(zip);
        return PLUGINS_DIR.resolve(topDir != null ? topDir : "unknown");
    }
}
