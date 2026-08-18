package com.agent.coding.backup;

import com.agent.coding.skill.SkillStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup storage: directory layout, zip read/write, HMAC signing and the
 * list / detail / delete / export operations. Ported from

 */
public class BackupStore {

    private static final Logger log = LoggerFactory.getLogger(BackupStore.class);

    public static final String META_FILE = "meta.json";
    public static final String PREFIX_WORKSPACES = "data/workspaces/";
    public static final String PREFIX_SECRETS = "data/secrets/";
    public static final String PREFIX_SKILL_POOL = "data/skill_pool/";
    public static final String PREFIX_CONFIG = "data/config.json";

    private static final Pattern BACKUP_ID_RE = Pattern.compile("^[a-zA-Z0-9._-]{1,200}$");
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);

    public static Path backupDir() {
        return SkillStore.WORKING_DIR.resolve(".backups");
    }

    public static Path keyFile() {
        return backupDir().resolve("signing.key");
    }

    public static String generateBackupId() {
        String ver = "majo";
        String ts = TS.format(Instant.now());
        String short8 = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "majo-" + ver + "-" + ts + "-" + short8;
    }

    public static void validateBackupId(String backupId) {
        if (backupId == null || !BACKUP_ID_RE.matcher(backupId).matches()) {
            throw new IllegalArgumentException("Invalid backup id: " + backupId);
        }
    }

    public static Path zipPath(String backupId) {
        return backupDir().resolve(backupId + ".zip");
    }

    /** Locate the zip for a backup id regardless of stored filename. */
    public static Path findZipPath(String backupId) {
        try {
            validateBackupId(backupId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Path canonical = zipPath(backupId);
        if (Files.isRegularFile(canonical)) {
            return canonical;
        }
        Path dir = backupDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.sorted().toList()) {
                String name = path.getFileName().toString();
                if (path.equals(canonical) || !name.endsWith(".zip") || !Files.isRegularFile(path)) {
                    continue;
                }
                try (ZipFile zf = new ZipFile(path.toFile())) {
                    ZipEntry meta = zf.getEntry(META_FILE);
                    if (meta == null) {
                        continue;
                    }
                    Map<?, ?> data = MAPPER.readValue(zf.getInputStream(meta), Map.class);
                    Object id = data.get("id");
                    if (backupId.equals(String.valueOf(id))) {
                        return path;
                    }
                } catch (Exception ignored) {
                    // not a valid backup, skip
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Signing (HMAC-SHA256 over canonical meta + zip entries)
    // ------------------------------------------------------------------

    private static synchronized byte[] signingKey() {
        Path keyPath = keyFile();
        try {
            if (Files.isRegularFile(keyPath)) {
                return Files.readAllBytes(keyPath);
            }
            Files.createDirectories(keyPath.getParent());
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);
            Files.write(keyPath, key, StandardOpenOption.CREATE_NEW);
            return key;
        } catch (IOException e) {
            log.warn("Failed to read/create signing key, using ephemeral key", e);
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);
            return key;
        }
    }

    private static byte[] canonicalMetaBytes(BackupMeta meta) {
        Map<String, Object> canonical = new java.util.TreeMap<>();
        canonical.put("id", meta.id);
        canonical.put("name", meta.name);
        canonical.put("description", meta.description == null ? "" : meta.description);
        canonical.put("created_at", meta.createdAt);
        canonical.put("version", meta.version == null ? "1" : meta.version);
        Map<String, Object> scope = new java.util.TreeMap<>();
        scope.put("include_agents", meta.scope.include_agents);
        scope.put("include_global_config", meta.scope.include_global_config);
        scope.put("include_secrets", meta.scope.include_secrets);
        scope.put("include_skill_pool", meta.scope.include_skill_pool);
        canonical.put("scope", scope);
        canonical.put("agent_count", meta.agentCount);
        canonical.put("majo_version", meta.majoVersion == null ? "" : meta.majoVersion);
        canonical.put("system_info", meta.systemInfo == null ? Map.of() : new java.util.TreeMap<>(meta.systemInfo));
        canonical.put("accepted_via_trust", Boolean.TRUE.equals(meta.acceptedViaTrust));
        try {
            return MAPPER.writeValueAsBytes(canonical);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize meta", e);
        }
    }

    public static String computeSignature(Path zip, BackupMeta meta) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey(), "HmacSHA256"));
            mac.update("META\u0000".getBytes(StandardCharsets.UTF_8));
            BackupMeta signingMeta = new BackupMeta(meta.id, meta.name, meta.description, meta.createdAt,
                meta.scope, meta.agentCount);
            signingMeta.majoVersion = meta.majoVersion;
            signingMeta.systemInfo = meta.systemInfo;
            signingMeta.acceptedViaTrust = meta.acceptedViaTrust;
            signingMeta.signature = null;
            mac.update(canonicalMetaBytes(signingMeta));
            mac.update((byte) 0);
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                List<ZipEntry> entries = new ArrayList<>();
                var it = zf.entries();
                while (it.hasMoreElements()) {
                    entries.add(it.nextElement());
                }
                entries.sort(java.util.Comparator.comparing(ZipEntry::getName));
                for (ZipEntry entry : entries) {
                    if (entry.isDirectory() || META_FILE.equals(entry.getName())) {
                        continue;
                    }
                    mac.update("ENTRY\u0000".getBytes(StandardCharsets.UTF_8));
                    mac.update(entry.getName().getBytes(StandardCharsets.UTF_8));
                    mac.update((byte) 0);
                    long size = 0;
                    try (InputStream in = zf.getInputStream(entry)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            size += n;
                            mac.update(buf, 0, n);
                        }
                    }
                    mac.update((byte) 0);
                    byte[] sizeBytes = new byte[8];
                    for (int i = 7; i >= 0; i--) {
                        sizeBytes[i] = (byte) (size & 0xFF);
                        size >>>= 8;
                    }
                    mac.update(sizeBytes);
                    mac.update((byte) 0);
                }
            }
            return "hmac-sha256-v1:" + toHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute signature", e);
        }
    }

    public static boolean verifySignature(Path zip, BackupMeta meta) {
        if (meta.signature == null || meta.signature.isBlank()) {
            return false;
        }
        try {
            String expected = computeSignature(zip, meta);
            String actual = meta.signature;
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ba.length != bb.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < ba.length; i++) {
            diff |= ba[i] ^ bb[i];
        }
        return diff == 0;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // List / detail / delete / export
    // ------------------------------------------------------------------

    public static List<BackupMeta> listBackups() {
        List<BackupMeta> results = new ArrayList<>();
        Path dir = backupDir();
        if (!Files.isDirectory(dir)) {
            return results;
        }
        try (var stream = Files.list(dir)) {
            for (Path f : stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".zip")).toList()) {
                BackupMeta meta = readMetaFromZip(f);
                if (meta != null) {
                    meta.signature = null;
                    results.add(meta);
                }
            }
        } catch (IOException ignored) {
        }
        results.sort((a, b) -> String.valueOf(b.createdAt).compareTo(String.valueOf(a.createdAt)));
        return results;
    }

    public static BackupMeta readMetaFromZip(Path zip) {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry meta = zf.getEntry(META_FILE);
            if (meta == null) {
                return null;
            }
            return MAPPER.readValue(zf.getInputStream(meta), BackupMeta.class);
        } catch (Exception e) {
            log.warn("Skipping invalid backup file: {}: {}", zip.getFileName(), e.getMessage());
            return null;
        }
    }

    public static BackupMeta getBackup(String backupId) {
        Path zip = findZipPath(backupId);
        if (zip == null) {
            return null;
        }
        BackupMeta meta = readMetaFromZip(zip);
        if (meta == null) {
            return null;
        }
        meta.workspaceStats = computeWorkspaceStats(zip);
        return meta;
    }

    private static Map<String, Object> computeWorkspaceStats(Path zip) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, String> agentJsonPaths = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(PREFIX_WORKSPACES)) {
                    String[] parts = name.split("/", 4);
                    if (parts.length >= 4) {
                        String aid = parts[2];
                        @SuppressWarnings("unchecked")
                        Map<String, Object> stat = (Map<String, Object>) stats.computeIfAbsent(aid, k -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("files", 0);
                            m.put("size", 0);
                            return m;
                        });
                        stat.put("files", ((Number) stat.get("files")).intValue() + 1);
                        stat.put("size", ((Number) stat.get("size")).longValue() + entry.getSize());
                        if ("agent.json".equals(parts[3])) {
                            agentJsonPaths.put(aid, name);
                        }
                    }
                }
            }
            for (Map.Entry<String, String> e : agentJsonPaths.entrySet()) {
                try {
                    Map<?, ?> data = MAPPER.readValue(zf.getInputStream(zf.getEntry(e.getValue())), Map.class);
                    Object name = data.get("name");
                    if (name != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> stat = (Map<String, Object>) stats.get(e.getKey());
                        stat.put("name", String.valueOf(name));
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return stats;
    }

    public static DeleteResult deleteBackups(List<String> ids) {
        DeleteResult result = new DeleteResult();
        for (String id : ids) {
            Path zip = findZipPath(id);
            if (zip == null) {
                result.failed.add(Map.of("id", id, "reason", "not found"));
                continue;
            }
            try {
                Files.deleteIfExists(zip);
                result.deleted.add(id);
            } catch (IOException e) {
                result.failed.add(Map.of("id", id, "reason", e.getMessage()));
            }
        }
        return result;
    }

    public static class DeleteResult {
        public List<String> deleted = new ArrayList<>();
        public List<Map<String, String>> failed = new ArrayList<>();
    }

    public static Path exportBackup(String backupId) {
        Path zip = findZipPath(backupId);
        if (zip == null) {
            throw new IllegalArgumentException("Backup not found: " + backupId);
        }
        return zip;
    }

    // ------------------------------------------------------------------
    // Zip helpers used by creator / importer / restorer
    // ------------------------------------------------------------------

    public static void writeEntry(Path zip, String name, byte[] data) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip,
                StandardOpenOption.APPEND))) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(data);
            zos.closeEntry();
        }
    }

    public static void zipDirectory(ZipOutputStream zos, Path root, Path base,
                                    String prefix, java.util.function.BiConsumer<String, Long> progress) throws IOException {
        //: every file under
        // root is added (rglob("*") with no name filtering). No skip list —
        // a blacklist here would silently drop content the reference
        // implementation always includes.
        List<Path> paths = new ArrayList<>();
        Files.walk(root).forEach(p -> {
            if (Files.isRegularFile(p)) {
                paths.add(p);
            }
        });
        paths.sort(java.util.Comparator.comparing(p -> p.toString()));
        int i = 0;
        for (Path p : paths) {
            String rel = base.relativize(p).toString().replace("\\", "/");
            zos.putNextEntry(new ZipEntry(prefix + rel));
            Files.copy(p, zos);
            zos.closeEntry();
            if (progress != null) {
                progress.accept(rel, (long) ++i);
            }
        }
    }

    public static void extractAll(ZipFile zf, Path destDir) throws IOException {
        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            Path target = destDir.resolve(entry.getName()).normalize();
            if (!target.startsWith(destDir.toAbsolutePath().normalize())) {
                throw new IOException("Unsafe zip path: " + entry.getName());
            }
            if (entry.isDirectory()) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                try (InputStream in = zf.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public static boolean zipHasPrefix(Path zip, String prefix) {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith(prefix)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    public static List<String> zipAgentIds(Path zip) {
        List<String> ids = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(PREFIX_WORKSPACES)) {
                    String[] parts = name.split("/", 4);
                    if (parts.length >= 4 && !ids.contains(parts[2])) {
                        ids.add(parts[2]);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return ids;
    }

    public static void copyZipEntry(ZipFile zf, String entryName, Path dest) throws IOException {
        ZipEntry entry = zf.getEntry(entryName);
        if (entry == null) {
            return;
        }
        Files.createDirectories(dest.getParent());
        try (InputStream in = zf.getInputStream(entry)) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static InputStream openZipEntry(Path zip, String entryName) throws IOException {
        ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip));
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.getName().equals(entryName)) {
                return zis;
            }
        }
        zis.close();
        return null;
    }
}
