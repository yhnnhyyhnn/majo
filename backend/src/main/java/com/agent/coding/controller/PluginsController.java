package com.agent.coding.controller;

import com.agent.coding.inbox.InboxStore;
import com.agent.coding.entity.PluginCacheEntity;
import com.agent.coding.entity.SyncConfigEntity;
import com.agent.coding.repository.PluginCacheRepository;
import com.agent.coding.repository.SyncConfigRepository;
import com.agent.coding.service.PluginRegistry;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final InboxStore inboxStore;
    private final PluginRegistry pluginRegistry;
    private final PluginCacheRepository pluginCacheRepo;
    private final SyncConfigRepository syncConfigRepo;

    public PluginsController(InboxStore inboxStore, PluginRegistry pluginRegistry,
                              PluginCacheRepository pluginCacheRepo,
                              SyncConfigRepository syncConfigRepo) {
        this.inboxStore = inboxStore;
        this.pluginRegistry = pluginRegistry;
        this.pluginCacheRepo = pluginCacheRepo;
        this.syncConfigRepo = syncConfigRepo;
    }

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
                Map<String, Object> m = MAPPER.readValue(mf, Map.class);
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
                Map<String, Object> manifest = MAPPER.readValue(mf, Map.class);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", manifest.getOrDefault("id", targetDir.getFileName().toString()));
                info.put("name", manifest.getOrDefault("name", ""));
                info.put("version", manifest.getOrDefault("version", "0.0.0"));
                info.put("status", "installed");
                inboxStore.appendEvent(null, "skill_autoupdate", targetDir.getFileName().toString(), "skill_installed",
                    "completed", "Plugin installed", "Plugin installed successfully: " + info.get("name"), "info", null);
                return ResponseEntity.ok(info);
            }
            return ResponseEntity.ok(Map.of("status", "installed", "id", targetDir.getFileName().toString()));
        } catch (Exception e) {
            log.error("Plugin install failed", e);
                inboxStore.appendEvent(null, "skill_autoupdate", "", "skill_failed",
                    "failed", "Plugin install failed", "Install failed: " + e.getMessage(), "error", null);
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

    // ── Helpers ────────────────────────────────────────────────────────
    private static String fetchUrl(String url) {
        try {
            var conn = (java.net.HttpURLConnection) new java.net.URI(url).toURL().openConnection();
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 400) return null;
            byte[] bytes;
            try (var in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                try (var gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                    return new String(gz.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> toCatalogResult(List<PluginCacheEntity> list) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated_at", list.get(0).getCachedAt().toString());
        result.put("error", null);
        Map<String, Object> plugins = new LinkedHashMap<>();
        for (var e : list) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", e.getId()); p.put("plugin_id", e.getPluginId());
            p.put("name", e.getName()); p.put("description", e.getDescription());
            p.put("version", e.getVersion()); p.put("author", e.getAuthor());
            p.put("kind", e.getKind()); p.put("size", e.getDisplaySize());
            p.put("sha256", e.getSha256()); p.put("install_url", e.getInstallUrl());
            p.put("installed", false); p.put("installed_version", null); p.put("upgrade_available", false);
            plugins.put(e.getId(), p);
        }
        result.put("plugins", plugins);
        return result;
    }

    private static ResponseEntity<Map<String, Object>> syncError(String msg) {
        return ResponseEntity.status(502).body(Map.of("detail", msg));
    }

    private static String pickEn(Object value) {
        if (value instanceof Map<?,?> m) {
            Object v = m.get("en-US");
            if (v == null) v = m.get("en");
            if (v == null) v = m.get("zh-CN");
            if (v == null) v = m.get("zh");
            return Objects.toString(v, "");
        }
        return Objects.toString(value, "");
    }

    private Map<String, Object> emptyCatalog() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("updated_at", null); r.put("plugins", Map.of()); r.put("error", "Failed to fetch catalog");
        return r;
    }

    // ── Plugin management stubs ────────────────────────────────────────
    @GetMapping("/plugins/catalog")
    public Object pluginsCatalog() {
        var list = pluginCacheRepo.findBySourceOrderByNameAsc("catalog");
        if (!list.isEmpty()) {
            return toCatalogResult(list);
        }
        return Map.of("plugins", Map.of(), "updated_at", null, "error", null);
    }

    @PostMapping("/plugins/catalog/sync")
    public ResponseEntity<?> syncCatalog() {
        try {
            String base = "https://download.qwenpaw.agentscope.io";
            String mainJson = fetchUrl(base + "/metadata/index.json");
            if (mainJson == null) return syncError("Failed to fetch main index");
            mainJson = mainJson.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
            Map<String, Object> main = MAPPER.readValue(mainJson, Map.class);
            if (main == null) return syncError("Invalid main index");
            @SuppressWarnings("unchecked")
            Map<String, Object> products = (Map<String, Object>) main.getOrDefault("products", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> pluginsProduct = (Map<String, Object>) products.getOrDefault("plugins", Map.of());
            String indexPath = Objects.toString(pluginsProduct.get("index_url"), "");
            if (indexPath.isBlank()) return syncError("No plugins index_url");

            String indexJson = fetchUrl(base + indexPath);
            if (indexJson == null) return syncError("Failed to fetch plugin index");
            indexJson = indexJson.replaceAll("[\\x00-\\x1F]", "");
            @SuppressWarnings("unchecked")
            Map<String, Object> index = MAPPER.readValue(indexJson, Map.class);
            if (index == null) return syncError("Invalid plugin index");

            pluginCacheRepo.deleteBySource("catalog");
            Object files = index.getOrDefault("files", Map.of());
            if (files instanceof Map<?,?> fm) {
                for (Object key : fm.keySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = (Map<String, Object>) fm.get(key);
                    if (entry == null) continue;
                    String fileId = key.toString();
                    String version = Objects.toString(entry.get("version"), "");
                    String pluginId = Objects.toString(entry.get("plugin_id"), fileId);
                    int dashIdx = fileId.lastIndexOf("-" + version);
                    if (dashIdx > 0) pluginId = fileId.substring(0, dashIdx);
                    var e = new PluginCacheEntity();
                    e.setId(fileId);
                    e.setSource("catalog");
                    e.setPluginId(pluginId);
                    e.setName(pickEn(entry.get("name")));
                    e.setDescription(pickEn(entry.get("description")));
                    e.setVersion(version);
                    e.setAuthor(Objects.toString(entry.get("author"), ""));
                    e.setKind(Objects.toString(entry.get("platform"), ""));
                    e.setDisplaySize(Objects.toString(entry.get("size"), ""));
                    e.setSha256(Objects.toString(entry.get("sha256"), ""));
                    String url = Objects.toString(entry.get("url"), "");
                    e.setInstallUrl(url.startsWith("/") ? base + url : url);
                    pluginCacheRepo.save(e);
                }
            }
            int count = pluginCacheRepo.findBySourceOrderByNameAsc("catalog").size();
            var config = new SyncConfigEntity("catalog", base + "/metadata/index.json");
            config.setSyncedCount(count);
            config.setSyncStatus("success");
            syncConfigRepo.save(config);
            return ResponseEntity.ok(Map.of("synced", count));
        } catch (Exception e) {
            log.warn("Catalog sync failed: {}", e.getMessage());
            markSyncFailed("catalog");
            return syncError(e.getMessage());
        }
    }

    @DeleteMapping("/plugins/{plugin_id}")
    public ResponseEntity<?> pluginDelete(@PathVariable String plugin_id) {
        Path target = findPluginDir(plugin_id);
        if (target == null) {
            return ResponseEntity.status(404)
                .body(Map.of("detail", "Plugin '" + plugin_id + "' is not loaded."));
        }
        try {
            try (var stream = Files.walk(target)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        } catch (Exception e) {
            return ResponseEntity.status(500)
                .body(Map.of("detail", "Plugin uninstallation failed: " + e.getMessage()));
        }
        if (pluginRegistry != null) pluginRegistry.rescan();
        inboxStore.appendEvent(null, "skill_autoupdate", plugin_id, "skill_uninstalled",
            "completed", "Plugin uninstalled", "Plugin '" + plugin_id + "' uninstalled successfully.", "info", null);

        return ResponseEntity.ok(Map.of(
            "id", plugin_id,
            "message", "Plugin '" + plugin_id + "' uninstalled successfully."
        ));
    }

    private Path findPluginDir(String pluginId) {
        File[] items = PLUGINS_DIR.toFile().listFiles(File::isDirectory);
        if (items == null) return null;
        for (File item : items) {
            // Exact directory name match
            if (item.getName().equals(pluginId)) return item.toPath();
            // Prefix match (e.g. "agent-kanban" → "agent-kanban-0.1.0")
            if (item.getName().startsWith(pluginId + "-")) return item.toPath();
            // Check plugin.json id field
            File mf = new File(item, "plugin.json");
            if (mf.exists()) {
                try {
                    Map<String, Object> manifest = MAPPER.readValue(mf, Map.class);
                    if (pluginId.equals(Objects.toString(manifest.get("id"), ""))) return item.toPath();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    @GetMapping("/plugins/{plugin_id}/files/{file_path}")
    public Map<String, String> pluginFile(@PathVariable String plugin_id, @PathVariable String file_path) { return Map.of("content", ""); }

    @GetMapping("/plugins/{plugin_id}/status")
    public Map<String, String> pluginStatus(@PathVariable String plugin_id) { return Map.of("status", "active"); }

    @GetMapping("/plugins/market/search")
    public Object pluginMarketSearch(
            @RequestParam(defaultValue = "1") int page_number,
            @RequestParam(defaultValue = "20") int page_size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String sort_by) {
        String q = (search != null && !search.isBlank()) ? search.toLowerCase() : null;
        String cat = (category != null && !category.isBlank()) ? category : null;
        var items = pluginCacheRepo.search("market", q, cat);
        int total = items.size();
        int totalPages = (int) Math.ceil((double) total / page_size);
        int from = (page_number - 1) * page_size;
        int to = Math.min(from + page_size, total);
        var page = from >= total ? List.of() : items.subList(from, to).stream()
            .map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId()); m.put("plugin_id", e.getPluginId());
                m.put("name", e.getName()); m.put("description", e.getDescription());
                m.put("version", e.getVersion()); m.put("author", e.getAuthor());
                m.put("kind", e.getKind()); m.put("size", e.getDisplaySize());
                m.put("install_url", e.getInstallUrl()); m.put("category", e.getCategory());
                return m;
            }).toList();
        return Map.of("items", page, "total", total,
            "page_number", page_number, "page_size", page_size, "total_pages", totalPages);
    }

    @PostMapping("/plugins/market/sync")
    public ResponseEntity<?> syncMarket() {
        try {
            String url = "https://platform.agentscope.io/openapi/v1/plugins?page_size=200";
            String json = fetchUrl(url);
            if (json == null) return syncError("Failed to fetch market");
            json = json.replaceAll("[\\x00-\\x1F]", "");
            var result = MAPPER.readValue(json, Map.class);
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) result.getOrDefault("items", List.of());
            pluginCacheRepo.deleteBySource("market");
            for (var entry : items) {
                var e = new PluginCacheEntity();
                e.setId(Objects.toString(entry.get("id"), Objects.toString(entry.get("plugin_id"), "") + "-" + entry.get("version")));
                e.setSource("market");
                e.setPluginId(Objects.toString(entry.get("plugin_id"), ""));
                e.setName(Objects.toString(entry.get("name"), ""));
                e.setDescription(Objects.toString(entry.get("description"), ""));
                e.setVersion(Objects.toString(entry.get("version"), ""));
                e.setAuthor(Objects.toString(entry.get("author"), ""));
                e.setKind(Objects.toString(entry.get("kind"), ""));
                e.setDisplaySize(Objects.toString(entry.get("size"), ""));
                e.setInstallUrl(Objects.toString(entry.get("install_url"), ""));
                e.setCategory(Objects.toString(entry.get("category"), ""));
                pluginCacheRepo.save(e);
            }
            var sConfig = new SyncConfigEntity("market", url);
            sConfig.setSyncedCount(items.size());
            sConfig.setSyncStatus("success");
            syncConfigRepo.save(sConfig);
            return ResponseEntity.ok(Map.of("synced", items.size()));
        } catch (Exception e) {
            log.warn("Market sync failed: {}", e.getMessage());
            markSyncFailed("market");
            return syncError(e.getMessage());
        }
    }

    private void markSyncFailed(String key) {
        syncConfigRepo.findById(key).ifPresent(c -> { c.setSyncStatus("failed"); syncConfigRepo.save(c); });
    }

    private static final List<Map<String, Object>> DEFAULT_SYNC_CONFIGS = List.of(
        buildDefaultConfig("catalog", "https://download.qwenpaw.agentscope.io/metadata/index.json"),
        buildDefaultConfig("market", "https://platform.agentscope.io/openapi/v1/plugins?page_size=200")
    );

    private static Map<String, Object> buildDefaultConfig(String key, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key); m.put("url", url);
        m.put("last_synced_at", null); m.put("synced_count", 0); m.put("status", "pending");
        return m;
    }

    @GetMapping("/plugins/sync-config")
    public List<Map<String, Object>> getSyncConfigs() {
        var stored = syncConfigRepo.findAll();
        Map<String, SyncConfigEntity> byKey = new java.util.HashMap<>();
        for (var s : stored) byKey.put(s.getConfigKey(), s);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (var defConfig : DEFAULT_SYNC_CONFIGS) {
            String key = (String) defConfig.get("key");
            var entity = byKey.get(key);
            result.add(entity != null ? toSyncConfigMap(entity) : defConfig);
        }
        return result;
    }

    @PutMapping("/plugins/sync-config")
    public ResponseEntity<?> updateSyncConfig(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        String url = body.get("url");
        if (key == null || key.isBlank() || url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "key and url are required"));
        }
        syncConfigRepo.findById(key).ifPresentOrElse(c -> {
            c.setUrl(url);
            syncConfigRepo.save(c);
        }, () -> {
            var c = new SyncConfigEntity(key, url);
            c.setSyncStatus("pending");
            syncConfigRepo.save(c);
        });
        var updated = syncConfigRepo.findById(key).map(this::toSyncConfigMap).orElse(Map.of());
        return ResponseEntity.ok(updated);
    }

    private Map<String, Object> toSyncConfigMap(SyncConfigEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", c.getConfigKey());
        m.put("url", c.getUrl());
        m.put("last_synced_at", c.getLastSyncedAt() != null ? c.getLastSyncedAt().toString() : null);
        m.put("synced_count", c.getSyncedCount());
        m.put("status", c.getSyncStatus());
        return m;
    }

    // ── Frontend plugin stubs ──────────────────────────────────────────
    @GetMapping("/frontend_plugin")
    public List<Map<String, String>> frontendPlugins() { return List.of(); }

    @GetMapping("/frontend_plugin/{plugin_id}/files/{file_path}")
    public Map<String, String> frontendPluginFile(@PathVariable String plugin_id, @PathVariable String file_path) { return Map.of("content", ""); }
}
