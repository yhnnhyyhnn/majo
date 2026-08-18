package com.agent.coding.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Skill store: path resolution, manifest JSON I/O with file locking and
 * atomic writes, SKILL.md frontmatter parsing, zip validation, path-safety
 * guards and metadata building.
 *
 *. 
 */
public class SkillStore {

    private static final Logger log = LoggerFactory.getLogger(SkillStore.class);

    /**
     * Data directory for agents.json, workspaces, skill_pool, plugins and
     * backups. Resolution:
     * 1. MAJO_WORKING_DIR env var, else
     * 2. {user.dir}/data/majo (project-local, independent of source tree).
     * Legacy data (agents.json / skill_pool under the old project root) is
     * migrated into this directory on first use.
     */
    public static final Path WORKING_DIR = resolveWorkingDir();

    private static Path resolveWorkingDir() {
        String env = System.getenv("MAJO_WORKING_DIR");
        Path dir;
        if (env != null && !env.isBlank()) {
            dir = Paths.get(env).toAbsolutePath().normalize();
        } else {
            dir = Paths.get(System.getProperty("user.dir"))
                .resolve("data").resolve("majo")
                .toAbsolutePath().normalize();
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Failed to create working dir {}", dir, e);
        }
        migrateLegacyData(dir);
        return dir;
    }

    private static void migrateLegacyData(Path target) {
        Path legacyRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (target.equals(legacyRoot)) {
            return;
        }
        Path workspacesBase = target.resolve("workspaces");
        String[] items = {"agents.json", "skill_pool", "workspaces", "plugins", "inbox_traces"};
        for (String name : items) {
            Path src = legacyRoot.resolve(name);
            Path dst = target.resolve(name);
            if (!Files.exists(src)) {
                continue;
            }
            if ("agents.json".equals(name)) {
                // Always rewrite so previously-migrated copies get workspace_dir fixed.
                try {
                    migrateAgentsJson(src, dst, workspacesBase);
                    log.info("Migrated legacy data: {} -> {}", src, dst);
                } catch (IOException e) {
                    log.warn("Failed to migrate {} to {}", src, dst, e);
                }
                continue;
            }
            if (Files.exists(dst)) {
                continue;
            }
            try {
                if (Files.isDirectory(src)) {
                    copyTree(src, dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                log.info("Migrated legacy data: {} -> {}", src, dst);
            } catch (IOException e) {
                log.warn("Failed to migrate {} to {}", src, dst, e);
            }
        }
    }

    /** Copy agents.json, rewriting profile workspace_dir entries that still
     *  point at the legacy project root into the new working directory. */
    @SuppressWarnings("unchecked")
    private static void migrateAgentsJson(Path src, Path dst, Path workspacesBase) throws IOException {
        Map<String, Object> config = readJson(src, Map.of());
        Object profilesObj = config.get("profiles");
        if (profilesObj instanceof Map<?, ?> profiles) {
            // Legacy workspace_dir values may point at the old data root
            // (user.dir) or the repository root above it (when the app was
            // previously launched from the project directory).
            String legacy = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();
            String legacyParent = Path.of(legacy).getParent() == null
                ? legacy : Path.of(legacy).getParent().toString();
            String base = workspacesBase.toString();
            for (Object raw : profiles.values()) {
                if (!(raw instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> profile = (Map<String, Object>) m;
                Object ws = profile.get("workspace_dir");
                if (!(ws instanceof String s)) {
                    continue;
                }
                String normalized = Path.of(s).toAbsolutePath().normalize().toString();
                String id = String.valueOf(profile.get("id"));
                if (normalized.equals(legacy) || normalized.equals(legacyParent)) {
                    profile.put("workspace_dir", Path.of(base).resolve(id).toString());
                } else if (normalized.startsWith(legacy + java.io.File.separator)) {
                    String rel = normalized.substring(legacy.length() + 1);
                    profile.put("workspace_dir", Path.of(base).resolve(rel).toString());
                } else if (normalized.startsWith(legacyParent + java.io.File.separator)) {
                    String rel = normalized.substring(legacyParent.length() + 1);
                    profile.put("workspace_dir", Path.of(base).resolve(rel).toString());
                }
            }
        }
        Files.createDirectories(dst.getParent());
        writeJsonAtomic(dst, config);
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            for (Path p : stream.toList()) {
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Max uncompressed zip payload. */
    public static final long MAX_ZIP_BYTES = 200L * 1024 * 1024;

    /** Well-known ignored artifacts when copying / enumerating skill dirs. */
    public static final Set<String> IGNORED_SKILL_ARTIFACTS = Set.of(
            "__pycache__", "__MACOSX", ".DS_Store", "Thumbs.db", "desktop.ini");

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TS_SUFFIX =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final java.util.regex.Pattern TIMESTAMP_SUFFIX_RE =
            java.util.regex.Pattern.compile("(-\\d{14})+$");

    private static final Yaml YAML = new Yaml();

    // ------------------------------------------------------------------
    // Path helpers
    // ------------------------------------------------------------------

    public static Path getSkillPoolDir() {
        return WORKING_DIR.resolve("skill_pool");
    }

    public static Path getWorkspaceSkillsDir(Path workspaceDir) {
        Path preferred = workspaceDir.resolve("skills");
        Path legacy = workspaceDir.resolve("skill");
        if (Files.isDirectory(preferred)) return preferred;
        if (Files.isDirectory(legacy)) {
            try {
                Files.move(legacy, preferred);
                return preferred;
            } catch (IOException e) {
                log.warn("Failed to migrate legacy skill dir: {}", legacy, e);
                return legacy;
            }
        }
        return preferred;
    }

    public static Path getWorkspaceSkillManifestPath(Path workspaceDir) {
        return workspaceDir.resolve("skill.json");
    }

    public static Path getPoolSkillManifestPath() {
        return getSkillPoolDir().resolve("skill.json");
    }

    /** Extra read-only skill roots (configured skill_paths) — majo: none. */
    public static List<Path> getExtraSkillDirs() {
        return List.of();
    }

    public static List<Path> getSkillPoolDirs() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(getSkillPoolDir());
        dirs.addAll(getExtraSkillDirs());
        return dirs;
    }

    public static Path resolvePoolSkillDir(String skillName) {
        String normalized;
        try {
            normalized = normalizeSkillDirName(skillName);
        } catch (SkillsError e) {
            return null;
        }
        for (Path root : getSkillPoolDirs()) {
            try {
                Path candidate = safeSkillDir(root, normalized);
                if (Files.isRegularFile(candidate.resolve("SKILL.md"))) return candidate;
            } catch (SkillsError e) {
                // continue
            }
        }
        return null;
    }

    public static boolean isPrimaryPoolSkillDir(Path skillDir) {
        try {
            return skillDir.toRealPath().getParent().equals(getSkillPoolDir().toRealPath());
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Frontmatter + introspection
    // ------------------------------------------------------------------

    /** Parse SKILL.md content; returns frontmatter map (empty on failure). */
    public static Map<String, Object> readFrontmatterFromContent(String content) {
        return readFrontmatterFromContent(content, "");
    }

    public static Map<String, Object> readFrontmatterFromContent(String content, String skillName) {
        try {
            return parseFrontmatter(content);
        } catch (Exception e) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("name", skillName);
            fallback.put("description", "");
            return fallback;
        }
    }

    public static Map<String, Object> readFrontmatterSafe(Path skillDir, String skillName) {
        if (skillName == null || skillName.isEmpty()) skillName = skillDir.getFileName().toString();
        try {
            String content = readTextFile(skillDir.resolve("SKILL.md"));
            return parseFrontmatter(content);
        } catch (Exception e) {
            log.warn("Failed to read SKILL.md frontmatter for '{}' at {}: {}", skillName, skillDir, e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("name", skillName);
            fallback.put("description", "");
            return fallback;
        }
    }

    /** Parse YAML frontmatter block delimited by '---' lines. */
    public static Map<String, Object> parseFrontmatter(String content) {
        if (content == null) return new HashMap<>();
        String text = content;
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end < 0) {
                // single-line block: --- {...} ---
                int close = text.indexOf("---", 3);
                if (close > 0) {
                    return parseYamlMap(text.substring(3, close).trim());
                }
                return new HashMap<>();
            }
            String yamlBlock = text.substring(3, end).trim();
            return parseYamlMap(yamlBlock);
        }
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYamlMap(String yamlBlock) {
        if (yamlBlock.isEmpty()) return new HashMap<>();
        Object parsed = YAML.load(yamlBlock);
        if (parsed instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
                if (e.getKey() != null) result.put(String.valueOf(e.getKey()), e.getValue());
            }
            return result;
        }
        return new HashMap<>();
    }

    public static String readTextFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    public static String getSkillMtime(Path skillDir) {
        try {
            long dirMtime = Files.getLastModifiedTime(skillDir).toMillis();
            Path md = skillDir.resolve("SKILL.md");
            long mdMtime = Files.isRegularFile(md)
                    ? Files.getLastModifiedTime(md).toMillis() : 0L;
            long mtime = Math.max(dirMtime, mdMtime);
            return ISO_UTC.format(Instant.ofEpochMilli(mtime)) + "Z";
        } catch (IOException e) {
            return "";
        }
    }

    public static String computeSkillMdHash(Path skillDir) {
        Path md = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) return "";
        try {
            String content = readTextFile(md);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String extractVersion(Map<String, Object> post) {
        Object metadata = post.get("metadata");
        Map<String, Object> meta = new HashMap<>();
        if (metadata instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) metadata).entrySet()) {
                if (e.getKey() != null) meta.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        for (String key : List.of("version", "metadata", "builtin_skill_version")) {
            Object value;
            if (key.equals("metadata")) {
                value = meta.get("version");
            } else if (key.equals("builtin_skill_version")) {
                value = meta.get("builtin_skill_version");
            } else {
                value = post.get(key);
            }
            if (value != null && !String.valueOf(value).isEmpty()) return String.valueOf(value);
        }
        return "";
    }

    // ------------------------------------------------------------------
    // Requirements extraction
    // ------------------------------------------------------------------

    public static Map<String, Object> extractRequirements(Map<String, Object> post) {
        Map<String, Object> requirements = new HashMap<>();
        List<String> requireBins = new ArrayList<>();
        List<String> requireEnvs = new ArrayList<>();
        Object metadata = post.get("metadata");
        if (metadata instanceof Map<?, ?>) {
            Object req = ((Map<?, ?>) metadata).get("qwenpaw");
            if (req instanceof Map<?, ?>) {
                Object rb = ((Map<?, ?>) req).get("requires");
                if (rb instanceof Map<?, ?>) {
                    Object bins = ((Map<?, ?>) rb).get("bin");
                    Object envs = ((Map<?, ?>) rb).get("env");
                    if (bins instanceof List<?>) for (Object o : (List<?>) bins) if (o != null) requireBins.add(String.valueOf(o));
                    if (envs instanceof List<?>) for (Object o : (List<?>) envs) if (o != null) requireEnvs.add(String.valueOf(o));
                }
            }
        }
        requirements.put("require_bins", requireBins);
        requirements.put("require_envs", requireEnvs);
        return requirements;
    }

    /** Build the manifest-facing metadata for one skill directory. */
    public static Map<String, Object> buildSkillMetadata(String skillName, Path skillDir,
                                                         String source, boolean protectedFlag) {
        Map<String, Object> post = readFrontmatterSafe(skillDir, skillName);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", skillName);
        metadata.put("description", post.getOrDefault("description", ""));
        metadata.put("version_text", extractVersion(post));
        metadata.put("commit_text", "");
        metadata.put("source", source);
        metadata.put("protected", protectedFlag);
        metadata.put("requirements", extractRequirements(post));
        metadata.put("updated_at", getSkillMtime(skillDir));
        return metadata;
    }

    public static String extractEmoji(Map<String, Object> post) {
        try {
            Object metadata = post.get("metadata");
            if (metadata instanceof Map<?, ?>) {
                Object qp = ((Map<?, ?>) metadata).get("qwenpaw");
                if (qp instanceof Map<?, ?>) {
                    Object emoji = ((Map<?, ?>) qp).get("emoji");
                    if (emoji != null && !String.valueOf(emoji).isEmpty()) return String.valueOf(emoji);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ------------------------------------------------------------------
    // Conflict-name suggestion
    // ------------------------------------------------------------------

    public static String suggestConflictName(String skillName, Set<String> existingNames) {
        String base = TIMESTAMP_SUFFIX_RE.matcher(skillName).replaceAll("");
        if (base.isEmpty()) base = skillName;
        Set<String> taken = existingNames == null ? Set.of() : existingNames;
        for (int i = 0; i < 100; i++) {
            String suffix = TS_SUFFIX.format(Instant.now());
            String candidate = base + "-" + suffix;
            if (!taken.contains(candidate)) return candidate;
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return base + "-" + TS_SUFFIX.format(Instant.now());
    }

    // ------------------------------------------------------------------
    // Path safety
    // ------------------------------------------------------------------

    public static String normalizeSkillDirName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) throw new SkillsError("Skill name cannot be empty");
        if (normalized.contains("\u0000")) throw new SkillsError("Skill name cannot contain NUL bytes");
        if (normalized.equals(".") || normalized.equals("..")) throw new SkillsError("Invalid skill name: " + normalized);
        if (normalized.contains("/") || normalized.contains("\\")) {
            throw new SkillsError("Skill name cannot contain path separators");
        }
        return normalized;
    }

    public static Path safeSkillDir(Path baseDir, String name) {
        String normalized = normalizeSkillDirName(name);
        Path candidate = baseDir.resolve(normalized).toAbsolutePath().normalize();
        Path baseResolved = baseDir.toAbsolutePath().normalize();
        if (!candidate.startsWith(baseResolved)) {
            throw new SkillsError("Unsafe skill path outside root: " + name);
        }
        return candidate;
    }

    public static Path safeChildPath(Path baseDir, String relativeName) {
        String normalized = (relativeName == null ? "" : relativeName).replace("\\", "/").trim();
        if (normalized.isEmpty()) throw new SkillsError("Skill file path cannot be empty");
        if (normalized.startsWith("/")) throw new SkillsError("Absolute path not allowed: " + relativeName);
        Path path = baseDir.resolve(normalized).toAbsolutePath().normalize();
        Path baseResolved = baseDir.toAbsolutePath().normalize();
        if (!path.startsWith(baseResolved)) {
            throw new SkillsError("Unsafe path outside skill directory: " + relativeName);
        }
        return path;
    }

    public static boolean isIgnoredSkillEntry(String name) {
        return IGNORED_SKILL_ARTIFACTS.contains(name) || name.startsWith("~");
    }

    // ------------------------------------------------------------------
    // JSON manifest I/O (file lock + atomic write)
    // ------------------------------------------------------------------

    private static Path lockPathFor(Path jsonPath) {
        return jsonPath.getParent().resolve("." + jsonPath.getFileName() + ".lock");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readJson(Path path, Map<String, Object> defaultPayload) {
        Path lockPath = lockPathFor(path);
        try {
            Files.createDirectories(lockPath.getParent());
        } catch (IOException e) {
            log.warn("Cannot create dir for {}", lockPath, e);
        }
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return readJsonUnlocked(path, defaultPayload);
        } catch (Exception e) {
            return deepCopy(defaultPayload);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonUnlocked(Path path, Map<String, Object> defaultPayload) {
        if (!Files.isRegularFile(path)) return deepCopy(defaultPayload);
        try {
            String text = readTextFile(path);
            if (text.isBlank()) return deepCopy(defaultPayload);
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Object.class);
            if (parsed instanceof Map<?, ?>) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
                    if (e.getKey() != null) result.put(String.valueOf(e.getKey()), e.getValue());
                }
                return result;
            }
            return deepCopy(defaultPayload);
        } catch (Exception e) {
            log.warn("Malformed JSON in {}, resetting to default", path);
            return deepCopy(defaultPayload);
        }
    }

    public static void writeJsonAtomic(Path path, Map<String, Object> payload) {
        Path lockPath = lockPathFor(path);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            log.warn("Cannot create dir for {}", path, e);
        }
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            writeJsonAtomicUnlocked(path, payload);
        } catch (Exception e) {
            log.warn("Failed to write JSON atomically to {}", path, e);
            throw new SkillsError("Failed to write manifest: " + path.getFileName());
        }
    }

    /** Atomic write without acquiring the lock (caller must hold it). */
    public static void writeJsonAtomicUnlocked(Path path, Map<String, Object> payload) {
        try {
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Failed to write JSON atomically to {}", path, e);
            throw new SkillsError("Failed to write manifest: " + path.getFileName());
        }
    }

    /** Read-modify-write under the manifest lock. */
    @SuppressWarnings("unchecked")
    public static <T> T mutateJson(Path path, Map<String, Object> defaultPayload,
                                   java.util.function.Function<Map<String, Object>, T> mutator) {
        Path lockPath = lockPathFor(path);
        try {
            Files.createDirectories(lockPath.getParent());
        } catch (IOException e) {
            log.warn("Cannot create dir for {}", lockPath, e);
        }
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Map<String, Object> payload = readJsonUnlocked(path, defaultPayload);
            T result = mutator.apply(payload);
            writeJsonAtomicUnlocked(path, payload);
            return result;
        } catch (Exception e) {
            throw new SkillsError("Failed to mutate manifest: " + path.getFileName());
        }
    }

    public static Map<String, Object> readWorkspaceManifest(Path workspaceDir) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("schema_version", "workspace-skill-manifest.v1");
        defaults.put("version", 0);
        defaults.put("skills", new LinkedHashMap<String, Object>());
        return readJson(getWorkspaceSkillManifestPath(workspaceDir), defaults);
    }

    public static Map<String, Object> readPoolManifest() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("schema_version", "skill-pool-manifest.v1");
        defaults.put("version", 0);
        defaults.put("skills", new LinkedHashMap<String, Object>());
        defaults.put("builtin_skill_names", new ArrayList<String>());
        return readJson(getPoolSkillManifestPath(), defaults);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet()) copy.put(e.getKey(), deepCopyValue(e.getValue()));
        return copy;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) copy.put(e.getKey(), deepCopyValue(e.getValue()));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object o : list) copy.add(deepCopyValue(o));
            return copy;
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Skill directory copy / create
    // ------------------------------------------------------------------

    public static void copySkillDir(Path source, Path target) {
        if (Files.exists(target)) {
            deleteRecursively(target);
        }
        copyRecursively(source, target, IGNORED_SKILL_ARTIFACTS);
    }

    public static void copyRecursively(Path source, Path target, Set<String> ignored) {
        try (var stream = Files.walk(source)) {
            for (Path src : stream.toList()) {
                Path rel = source.relativize(src);
                boolean skip = false;
                for (Path part : rel) {
                    if (ignored.contains(part.toString())) { skip = true; break; }
                }
                if (skip) continue;
                Path dst = target.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new SkillsError("Failed to copy skill dir: " + e.getMessage());
        }
    }

    public static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>(stream.toList());
            Collections.reverse(paths);
            for (Path p : paths) Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", dir, e.getMessage());
        }
    }

    /** Write files from a {name: content-or-nested-map} tree. */
    @SuppressWarnings("unchecked")
    public static void createFilesFromTree(Path baseDir, Map<String, Object> tree) {
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            Path path = safeChildPath(baseDir, e.getKey());
            Object value = e.getValue();
            if (value instanceof Map<?, ?> sub) {
                Map<String, Object> nested = new HashMap<>();
                for (Map.Entry<?, ?> f : sub.entrySet()) {
                    if (f.getKey() != null) nested.put(String.valueOf(f.getKey()), f.getValue());
                }
                try { Files.createDirectories(path); } catch (IOException ex) { throw new SkillsError("Cannot mkdir " + path); }
                createFilesFromTree(path, nested);
            } else if (value == null || value instanceof String) {
                try {
                    Files.createDirectories(path.getParent());
                    Files.write(path, String.valueOf(value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
                } catch (IOException ex) {
                    throw new SkillsError("Failed to write skill file: " + path);
                }
            } else {
                throw new SkillsError("Invalid tree value for " + e.getKey());
            }
        }
    }

    // ------------------------------------------------------------------
    // Zip handling
    // ------------------------------------------------------------------

    public static void extractAndValidateZip(byte[] data, Path tmpDir) {
        try (var zf = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            long total = 0;
            List<ZipEntryInfo> entries = new ArrayList<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zf.getNextEntry()) != null) {
                total += entry.getSize();
                if (total > MAX_ZIP_BYTES) {
                    throw new SkillsError("Uncompressed zip exceeds 200MB limit");
                }
                Path target = tmpDir.resolve(entry.getName()).toAbsolutePath().normalize();
                Path root = tmpDir.toAbsolutePath().normalize();
                if (!target.startsWith(root)) {
                    throw new SkillsError("Unsafe path in zip: " + entry.getName());
                }
                entries.add(new ZipEntryInfo(entry, target));
            }
            // Second pass: extract (ZipInputStream already read entries; re-extract from bytes)
            try (var zf2 = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
                java.util.zip.ZipEntry e2;
                while ((e2 = zf2.getNextEntry()) != null) {
                    Path target = tmpDir.resolve(e2.getName()).toAbsolutePath().normalize();
                    Path root = tmpDir.toAbsolutePath().normalize();
                    if (!target.startsWith(root)) continue;
                    if (e2.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zf2, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (SkillsError e) {
            throw e;
        } catch (Exception e) {
            throw new SkillsError("Invalid zip archive: " + e.getMessage());
        }
    }

    private record ZipEntryInfo(java.util.zip.ZipEntry entry, Path target) {}

    /**
     * Extract and validate a skill zip. Returns (tmpDir, found[(skillDir, skillName)]).
     * Naming rule: single-skill zips use frontmatter name; multi-skill zips apply
     * the same rule per top-level skill directory.
     */
    public static List<Object[]> extractZipSkills(byte[] data) {
        boolean isZip;
        try (java.util.zip.ZipInputStream probe = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            isZip = probe.getNextEntry() != null;
        } catch (Exception e) {
            isZip = false;
        }
        if (!isZip) {
            throw new SkillsError("Uploaded file is not a valid zip archive");
        }
        Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("majo_skill_upload_");
        } catch (IOException e) {
            throw new SkillsError("Cannot create temp dir for zip extraction");
        }
        try {
            extractAndValidateZip(data, tmpDir);
        } catch (SkillsError e) {
            SkillStore.deleteRecursively(tmpDir);
            throw e;
        }
        List<Path> realEntries = new ArrayList<>();
        try (var stream = Files.list(tmpDir)) {
            for (Path p : stream.toList()) {
                if (!isIgnoredSkillEntry(p.getFileName().toString())) realEntries.add(p);
            }
        } catch (IOException ignored) {}
        Path extractRoot = tmpDir;
        if (realEntries.size() == 1 && Files.isDirectory(realEntries.get(0))) {
            extractRoot = realEntries.get(0);
        }
        List<Object[]> found = new ArrayList<>();
        if (Files.isRegularFile(extractRoot.resolve("SKILL.md"))) {
            found.add(new Object[]{extractRoot, resolveSkillName(extractRoot)});
        } else {
            List<Path> children = new ArrayList<>();
            try (var stream = Files.list(extractRoot)) {
                for (Path p : stream.toList()) {
                    if (!isIgnoredSkillEntry(p.getFileName().toString())
                            && Files.isDirectory(p)
                            && Files.isRegularFile(p.resolve("SKILL.md"))) {
                        children.add(p);
                    }
                }
            } catch (IOException ignored) {}
            children.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path p : children) found.add(new Object[]{p, resolveSkillName(p)});
        }
        if (found.isEmpty()) {
            SkillStore.deleteRecursively(tmpDir);
            throw new SkillsError("No valid skills found in uploaded zip");
        }
        return found;
    }

    /** Resolve import-time target name: frontmatter name when present, else dir name. */
    public static String resolveSkillName(Path skillDir) {
        Map<String, Object> post = readFrontmatterSafe(skillDir, null);
        Object name = post.get("name");
        if (name != null && !String.valueOf(name).trim().isEmpty()) {
            return String.valueOf(name).trim();
        }
        return skillDir.getFileName().toString();
    }

    /** Resolve workspace id + display name (majo: dir name; no agent config). */
    public static Map<String, String> getWorkspaceIdentity(Path workspaceDir) {
        String workspaceId = workspaceDir.getFileName().toString();
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("workspace_id", workspaceId);
        identity.put("workspace_name", workspaceId);
        return identity;
    }

    // ------------------------------------------------------------------
    // Manifest entry classification
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static boolean isPoolBuiltinEntry(Map<String, Object> entry) {
        if (entry == null || entry.isEmpty()) return false;
        Object source = entry.get("source");
        return "builtin".equals(String.valueOf(source == null ? "" : source));
    }

    @SuppressWarnings("unchecked")
    public static String[] classifyPoolSkillSource(String skillName, Path skillDir,
                                                   Map<String, Object> existing, List<String> builtinNames) {
        // Returns [source, protected] where protected is always "false" in this classification.
        if (existing != null && !existing.isEmpty() && isPoolBuiltinEntry(existing)) {
            return new String[]{"builtin", "false"};
        }
        if (!builtinNames.contains(skillName)) return new String[]{"customized", "false"};
        if (existing != null && !existing.isEmpty()) return new String[]{"customized", "false"};
        Map<String, Object> post = readFrontmatterSafe(skillDir, skillName);
        String poolVersion = extractVersion(post);
        if (!poolVersion.isEmpty()) return new String[]{"builtin", "false"};
        return new String[]{"customized", "false"};
    }

    /** Recursively describe a directory tree (files -> null, dirs -> nested map). */
    public static Map<String, Object> directoryTree(Path directory) {
        Map<String, Object> tree = new LinkedHashMap<>();
        if (directory == null || !Files.isDirectory(directory)) return tree;
        try (var stream = Files.list(directory)) {
            List<Path> items = new ArrayList<>(stream.toList());
            items.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path item : items) {
                if (Files.isRegularFile(item)) {
                    tree.put(item.getFileName().toString(), null);
                } else if (Files.isDirectory(item)) {
                    tree.put(item.getFileName().toString(), directoryTree(item));
                }
            }
        } catch (IOException ignored) {}
        return tree;
    }

    /** Import a skill directory into a target root; returns false on conflict/validation failure. */
    public static boolean importSkillDir(Path srcDir, Path targetRoot, String skillName) {
        Map<String, Object> post = readFrontmatterSafe(srcDir, skillName);
        Object name = post.get("name");
        Object description = post.get("description");
        if (name == null || String.valueOf(name).trim().isEmpty()
                || description == null || String.valueOf(description).trim().isEmpty()) {
            return false;
        }
        Path targetDir = targetRoot.resolve(skillName);
        if (Files.exists(targetDir)) return false;
        copySkillDir(srcDir, targetDir);
        return true;
    }

    // ------------------------------------------------------------------
    // SKILL.md rendering
    // ------------------------------------------------------------------

    /** Render a SKILL.md document from name + description + body. */
    public static String renderSkillMd(String proposedName, String description, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(yamlEscape(proposedName)).append("\n");
        sb.append("description: ").append(yamlEscape(description == null ? "" : description)).append("\n");
        sb.append("---\n\n");
        if (body != null) sb.append(body);
        return sb.toString();
    }

    private static String yamlEscape(String value) {
        if (value == null) return "''";
        String v = value.trim();
        if (v.isEmpty()) return "''";
        if (v.contains("\n") || v.startsWith(" ") || v.endsWith(" ") || v.contains(":")) {
            return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return v;
    }
}
