package com.agent.coding.controller;

import com.agent.coding.inbox.InboxStore;
import com.agent.coding.dto.MarketPluginEntry;
import com.agent.coding.dto.MarketSearchResponse;
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
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
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

    /**
     * Build the catalog response matching the reference backend contract:
     * {@code plugins} is a JSON ARRAY (sorted by kind, then name), not a map.
     * Installed state is derived from plugin.json manifests on disk, exactly
     * like the reference {@code _installed_plugin_ids()} + {@code _is_upgrade_available()}.
     */
    private Map<String, Object> toCatalogResult(List<PluginCacheEntity> list) {
        Map<String, String> installedVersions = installedPluginVersions();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated_at", list.get(0).getCachedAt().toString());
        result.put("error", null);
        List<Map<String, Object>> plugins = new ArrayList<>();
        for (var e : list) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", e.getId()); p.put("plugin_id", e.getPluginId());
            p.put("name", e.getName()); p.put("description", e.getDescription());
            p.put("version", e.getVersion()); p.put("author", e.getAuthor());
            p.put("kind", e.getKind()); p.put("size", e.getDisplaySize());
            p.put("sha256", e.getSha256()); p.put("install_url", e.getInstallUrl());
            String installedVersion = installedVersions.get(e.getPluginId());
            boolean installed = installedVersion != null;
            p.put("installed", installed);
            p.put("installed_version", installed ? installedVersion : null);
            p.put("upgrade_available", installed
                && isUpgradeAvailable(installedVersion, e.getVersion()));
            plugins.add(p);
        }
        plugins.sort(Comparator
            .comparing((Map<String, Object> m) -> String.valueOf(m.getOrDefault("kind", "")))
            .thenComparing(m -> String.valueOf(m.getOrDefault("name", ""))));
        result.put("plugins", plugins);
        return result;
    }

    /**
     * Scan PLUGINS_DIR for plugin.json manifests and return
     * {@code {plugin_id: installed_version}}, mirroring the reference
     * {@code _installed_plugin_ids()}.
     */
    private Map<String, String> installedPluginVersions() {
        Map<String, String> installed = new HashMap<>();
        File[] items = PLUGINS_DIR.toFile().listFiles(File::isDirectory);
        if (items == null) return installed;
        for (File item : items) {
            File mf = new File(item, "plugin.json");
            if (!mf.exists()) continue;
            try {
                Map<String, Object> manifest = MAPPER.readValue(mf, Map.class);
                String id = Objects.toString(manifest.getOrDefault("id", item.getName()), "");
                if (!id.isBlank()) {
                    installed.put(id, Objects.toString(manifest.getOrDefault("version", "0.0.0"), "0.0.0"));
                }
            } catch (Exception ignored) { }
        }
        return installed;
    }

    /**
     * True when the catalog version is strictly newer than the installed
     * version (numeric dot-segment compare; falls back to inequality when
     * either version is not parseable) — mirrors reference {@code _is_upgrade_available()}.
     */
    private static boolean isUpgradeAvailable(String installedVersion, String catalogVersion) {
        if (installedVersion == null || installedVersion.isBlank()
            || catalogVersion == null || catalogVersion.isBlank()) {
            return false;
        }
        int cmp = compareVersions(catalogVersion, installedVersion);
        return cmp != Integer.MIN_VALUE ? cmp > 0 : !installedVersion.equals(catalogVersion);
    }

    private static int compareVersions(String a, String b) {
        try {
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");
            int n = Math.max(pa.length, pb.length);
            for (int i = 0; i < n; i++) {
                int va = i < pa.length ? Integer.parseInt(pa[i].replaceAll("[^0-9].*$", "")) : 0;
                int vb = i < pb.length ? Integer.parseInt(pb[i].replaceAll("[^0-9].*$", "")) : 0;
                if (va != vb) return Integer.compare(va, vb);
            }
            return 0;
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE; // sentinel: caller falls back to string inequality
        }
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

    // ── Plugin management stubs ────────────────────────────────────────
    @GetMapping("/plugins/catalog")
    public Object pluginsCatalog() {
        var list = pluginCacheRepo.findBySourceOrderByNameAsc("catalog");
        if (!list.isEmpty()) {
            return toCatalogResult(list);
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("plugins", List.of());
        empty.put("updated_at", null);
        empty.put("error", null);
        return empty;
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
    public MarketSearchResponse pluginMarketSearch(
            @RequestParam(defaultValue = "1") int page_number,
            @RequestParam(defaultValue = "20") int page_size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String sort_by) {
        try {
            String q = (search != null && !search.isBlank()) ? search.toLowerCase() : null;
            String cat = mapMarketCategory(category);
            boolean emptyByDesign = category != null && !category.isBlank() && cat == null;
            List<PluginCacheEntity> items = emptyByDesign ? List.of()
                : pluginCacheRepo.search("market", q, cat);

            String sort = sort_by != null ? sort_by : "downloads";
            Comparator<PluginCacheEntity> cmp;
            switch (sort) {
                case "downloads" -> cmp = Comparator.comparing(
                    (PluginCacheEntity e) -> e.getDownloads() == null ? Long.MIN_VALUE : e.getDownloads(),
                    Comparator.reverseOrder());
                case "fauvarate" -> cmp = Comparator.comparing(
                    (PluginCacheEntity e) -> e.getViewCount() == null ? Long.MIN_VALUE : e.getViewCount(),
                    Comparator.reverseOrder());
                default -> cmp = Comparator.comparing(
                    (PluginCacheEntity e) -> e.getSortRank() == null ? Integer.MAX_VALUE : e.getSortRank());
            }
            List<PluginCacheEntity> sorted = new ArrayList<>(items);
            sorted.sort(cmp);

            int total = sorted.size();
            int from = (page_number - 1) * page_size;
            int to = Math.min(from + page_size, total);
            List<MarketPluginEntry> page = from >= total ? List.of()
                : sorted.subList(from, to).stream().map(PluginsController::toMarketEntry).toList();
            return MarketSearchResponse.ok(total, page);
        } catch (Exception e) {
            log.warn("Market search failed: {}", e.getMessage());
            return MarketSearchResponse.error(e.getMessage());
        }
    }

    private static String mapMarketCategory(String category) {
        if (category == null || category.isBlank()) return null;
        return switch (category) {
            case "app" -> "App";
            case "agent-tool" -> "Tool";
            case "provider" -> "Provider";
            case "command" -> "Command";
            case "hook" -> null; // platform has no Hook category
            case "frontend" -> "Frontend";
            case "general" -> "General";
            default -> category;
        };
    }

    private static MarketPluginEntry toMarketEntry(PluginCacheEntity e) {
        Map<String, Map<String, String>> locales = parseLocales(e.getLocales());
        List<String> compat = parseCompatLabels(e.getCompatLabels());
        return new MarketPluginEntry(
            e.getId(),
            e.getDisplayName() != null ? e.getDisplayName() : e.getName(),
            e.getDeveloper(),
            e.getOwner(),
            e.getVersion(),
            e.getLogoUrl(),
            e.getDownloads() == null ? 0 : e.getDownloads(),
            e.getViewCount() == null ? 0 : e.getViewCount(),
            e.getDetailsUrl(),
            locales,
            compat,
            Boolean.TRUE.equals(e.getFeatured()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> parseLocales(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = MAPPER.readValue(json, Map.class);
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> lm) {
                    Map<String, String> loc = new LinkedHashMap<>();
                    Object desc = lm.get("description");
                    Object cat = lm.get("category");
                    if (desc != null) loc.put("description", desc.toString());
                    if (cat != null) loc.put("category", cat.toString());
                    out.put(entry.getKey(), loc);
                }
            }
            return out;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static List<String> parseCompatLabels(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    @PostMapping("/plugins/market/sync")
    public ResponseEntity<?> syncMarket() {
        try {
            String base = "https://platform.agentscope.io/openapi/v1/plugins";
            List<Map<String, Object>> all = new ArrayList<>();
            int page = 1;
            int total = -1;
            do {
                String json = fetchUrl(base + "?page_number=" + page + "&page_size=100");
                if (json == null) return syncError("Failed to fetch market");
                json = json.replaceAll("[\\x00-\\x1F]", "");
                @SuppressWarnings("unchecked")
                Map<String, Object> result = MAPPER.readValue(json, Map.class);
                if (!Boolean.TRUE.equals(result.get("success"))) {
                    return syncError("Market API error: " + result.get("message"));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.getOrDefault("data", Map.of());
                total = ((Number) data.getOrDefault("total", 0)).intValue();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) data.getOrDefault("plugins", List.of());
                if (items.isEmpty()) break;
                all.addAll(items);
                page++;
            } while (all.size() < total && page <= 10);

            pluginCacheRepo.deleteBySource("market");
            int rank = 0;
            for (var entry : all) {
                var e = new PluginCacheEntity();
                String id = Objects.toString(entry.get("id"), "");
                e.setId(id);
                e.setSource("market");
                e.setPluginId(id.startsWith("@") ? id.substring(1) : id);
                String displayName = Objects.toString(entry.get("display_name"), "");
                e.setName(displayName);
                e.setDisplayName(displayName);
                e.setVersion(Objects.toString(entry.get("version"), ""));
                e.setDeveloper(Objects.toString(entry.get("developer"), ""));
                e.setOwner(Objects.toString(entry.get("owner"), ""));
                e.setLogoUrl(null);
                e.setDownloads(toLong(entry.get("downloads")));
                e.setViewCount(toLong(entry.get("view_count")));
                e.setDetailsUrl(Objects.toString(entry.get("details_url"), ""));
                e.setFeatured(Boolean.TRUE.equals(entry.get("is_featured")));
                Object labels = entry.get("qwenpaw_compat_labels");
                e.setCompatLabels(labels != null ? MAPPER.writeValueAsString(labels) : "[]");
                Object locales = entry.get("locales");
                e.setLocales(locales != null ? MAPPER.writeValueAsString(locales) : "{}");
                e.setCategory(extractLocaleValue(locales, "category"));
                e.setDescription(extractLocaleValue(locales, "description"));
                e.setSortRank(rank++);
                pluginCacheRepo.save(e);
            }
            var sConfig = new SyncConfigEntity("market", base);
            sConfig.setSyncedCount(all.size());
            sConfig.setSyncStatus("success");
            syncConfigRepo.save(sConfig);
            return ResponseEntity.ok(Map.of("synced", all.size()));
        } catch (Exception e) {
            log.warn("Market sync failed: {}", e.getMessage());
            markSyncFailed("market");
            return syncError(e.getMessage());
        }
    }

    private static Long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return null;
    }

    private static String extractLocaleValue(Object locales, String key) {
        if (!(locales instanceof Map<?, ?> lm)) return "";
        for (Object lang : new Object[]{"en", "zh"}) {
            Object v = lm.get(lang);
            if (v instanceof Map<?, ?> m && m.get(key) != null) {
                return Objects.toString(m.get(key), "");
            }
        }
        for (Object v : lm.values()) {
            if (v instanceof Map<?, ?> m && m.get(key) != null) {
                return Objects.toString(m.get(key), "");
            }
        }
        return "";
    }

    private void markSyncFailed(String key) {
        syncConfigRepo.findById(key).ifPresent(c -> { c.setSyncStatus("failed"); syncConfigRepo.save(c); });
    }

    private static final List<Map<String, Object>> DEFAULT_SYNC_CONFIGS = List.of(
        buildDefaultConfig("catalog", "https://download.qwenpaw.agentscope.io/metadata/index.json"),
        buildDefaultConfig("market", "https://platform.agentscope.io/openapi/v1/plugins")
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

    // ── Agent-scoped plugins (port of qwenpaw agent_scoped /agents/{agentId}/plugins) ──
    @GetMapping("/agents/{agentId}/plugins")
    public Object agentPlugins(@PathVariable String agentId) { return listPlugins(); }

    @PostMapping("/agents/{agentId}/plugins/install")
    public Object agentPluginInstall(@PathVariable String agentId,
                                     @RequestBody Map<String, Object> body) { return installPlugin(body); }

    @PostMapping("/agents/{agentId}/plugins/upload")
    public Object agentPluginUpload(@PathVariable String agentId) { return uploadPlugin(); }

    @GetMapping("/agents/{agentId}/plugins/catalog")
    public Object agentPluginCatalog(@PathVariable String agentId) { return pluginsCatalog(); }

    @DeleteMapping("/agents/{agentId}/plugins/{plugin_id}")
    public Object agentPluginDelete(@PathVariable String agentId,
                                    @PathVariable String plugin_id) { return pluginDelete(plugin_id); }

    @GetMapping("/agents/{agentId}/plugins/{plugin_id}/files/{file_path}")
    public Object agentPluginFile(@PathVariable String agentId,
                                  @PathVariable String plugin_id,
                                  @PathVariable String file_path) { return pluginFile(plugin_id, file_path); }

    @GetMapping("/agents/{agentId}/plugins/{plugin_id}/status")
    public Object agentPluginStatus(@PathVariable String agentId,
                                    @PathVariable String plugin_id) { return pluginStatus(plugin_id); }

    @GetMapping("/agents/{agentId}/plugins/market/search")
    public Object agentPluginMarket(@PathVariable String agentId,
                                    @RequestParam(defaultValue = "1") int page_number,
                                    @RequestParam(defaultValue = "20") int page_size,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String sort_by) {
        return pluginMarketSearch(page_number, page_size, search, category, sort_by);
    }
}
