package com.agent.coding.skill;

import com.agent.coding.agent.AgentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builtin skill discovery, manifest reconciliation, import/update of packaged
 * builtins and workspace enumeration.
 *
 *. 
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    public static final List<String> BUILTIN_SKILL_LANGUAGES = List.of("en", "zh");
    private static final java.util.regex.Pattern BUILTIN_DIR_RE =
            java.util.regex.Pattern.compile("^(?<name>.+)-(?<language>en|zh)$");

    private SkillRegistry() {}

    private static final AtomicBoolean BUILTIN_EXTRACT_ATTEMPTED = new AtomicBoolean(false);

    /**
     * Materialize the packaged builtin skills from the classpath into a real
     * filesystem directory next to the pool ({@link #getBuiltinSkillsDir()}).
     *
     * <p>When running from a JAR the classpath resource lives inside the archive;
     * java.nio reads through the zip filesystem are unreliable there (some JDK
     * builds throw {@code IndexOutOfBoundsException} on readAllBytes/copy).
     * Extracting once to disk makes every later read plain file I/O, which is
     * what {@link #iterPackagedBuiltinDirs()} prefers anyway.</p>
     */
    private static void ensureBuiltinSkillsMaterialized() {
        if (BUILTIN_EXTRACT_ATTEMPTED.getAndSet(true)) return;
        Path target = getBuiltinSkillsDir();
        if (Files.isDirectory(target)) {
            try (var stream = Files.list(target)) {
                if (stream.findAny().isPresent()) return;
            } catch (IOException ignored) {
            }
        }
        try {
            Files.createDirectories(target);
            ClassLoader cl = SkillRegistry.class.getClassLoader();
            var resources = cl.getResources("builtin-skills");
            boolean copied = false;
            while (resources.hasMoreElements()) {
                copied |= extractClasspathDir(resources.nextElement(), target);
            }
            if (!copied) {
                log.warn("No builtin-skills classpath resource found; pool builtins will be empty");
            }
        } catch (Exception e) {
            log.warn("Failed to materialize builtin-skills to {}: {}", target, e.getMessage());
        }
    }

    /** Copy one classpath resource directory (file: or jar: URL) into {@code targetDir}. */
    private static boolean extractClasspathDir(java.net.URL url, Path targetDir) {
        try {
            if ("file".equals(url.getProtocol())) {
                Path src = Paths.get(url.toURI());
                if (!Files.isDirectory(src)) return false;
                copyPathTree(src, targetDir);
                return true;
            }
            if ("jar".equals(url.getProtocol())) {
                // jar:file:/opt/majo.jar!/builtin-skills -> read the archive with a
                // plain ZipInputStream (no zip filesystem provider involved).
                String spec = url.getFile();
                int bang = spec.indexOf("!/");
                if (bang <= 0) return false;
                String prefix = spec.substring(bang + 2).replace("\\", "/");
                java.net.URL jarUrl = new java.net.URL(spec.substring(0, bang));
                try (java.util.zip.ZipInputStream zis =
                             new java.util.zip.ZipInputStream(jarUrl.openStream())) {
                    java.util.zip.ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        String name = e.getName().replace('\\', '/');
                        if (name.equals(prefix) || !name.startsWith(prefix + "/")) continue;
                        String rel = name.substring(prefix.length() + 1);
                        if (rel.isEmpty()) continue;
                        Path dst = targetDir.resolve(rel).normalize();
                        if (!dst.startsWith(targetDir.normalize())) continue; // path safety
                        if (e.isDirectory()) {
                            Files.createDirectories(dst);
                        } else {
                            Files.createDirectories(dst.getParent());
                            Files.copy(zis, dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to extract classpath dir {}: {}", url, e.getMessage());
        }
        return false;
    }

    private static void copyPathTree(Path src, Path targetDir) throws IOException {
        if (!Files.isDirectory(src)) return;
        try (var stream = Files.walk(src)) {
            for (Path s : stream.toList()) {
                Path rel = src.relativize(s);
                if (rel.getNameCount() == 0) continue;
                Path dst = targetDir.resolve(rel.toString());
                if (Files.isDirectory(s)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(s, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Packaged builtin discovery (resources/builtin-skills)
    // ------------------------------------------------------------------

    public static Path getBuiltinSkillsDir() {
        // Prefer filesystem copy next to the pool for runtime use, else classpath resource.
        Path candidate = SkillStore.getSkillPoolDir().getParent().resolve("builtin-skills");
        return candidate;
    }

    public static List<Path> iterPackagedBuiltinDirs() {
        List<Path> dirs = new ArrayList<>();
        ensureBuiltinSkillsMaterialized();
        Path fs = getBuiltinSkillsDir();
        if (Files.isDirectory(fs)) {
            try (var stream = Files.list(fs)) {
                stream.filter(Files::isDirectory).forEach(dirs::add);
            } catch (IOException ignored) {}
        }
        if (dirs.isEmpty()) {
            // classpath resource lookup
            try {
                var resources = SkillRegistry.class.getClassLoader()
                        .getResources("builtin-skills");
                while (resources.hasMoreElements()) {
                    java.net.URL url = resources.nextElement();
                    try {
                        Path p = Paths.get(url.toURI());
                        if (Files.isDirectory(p)) {
                            try (var stream = Files.list(p)) {
                                stream.filter(Files::isDirectory).forEach(dirs::add);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return dirs;
    }

    /** Parse builtin identity from a dir name like "browser_cdp-en". Returns null if not matching. */
    public static BuiltinVariant parseBuiltinIdentity(Path dir) {
        String name = dir.getFileName().toString();
        var m = BUILTIN_DIR_RE.matcher(name);
        if (!m.matches()) return null;
        String skillName = m.group("name");
        String language = m.group("language");
        if (!Files.isRegularFile(dir.resolve("SKILL.md"))) return null;
        Map<String, Object> post = SkillStore.readFrontmatterSafe(dir, skillName);
        return new BuiltinVariant(
                skillName, language, dir,
                dir.resolve("SKILL.md"),
                str(post.get("description")),
                SkillStore.extractVersion(post));
    }

    public record BuiltinVariant(String skillName, String language, Path dir,
                                 Path skillMdPath, String description, String versionText) {}

    public static List<BuiltinVariant> iterPackagedBuiltinVariants() {
        List<BuiltinVariant> variants = new ArrayList<>();
        for (Path dir : iterPackagedBuiltinDirs()) {
            BuiltinVariant v = parseBuiltinIdentity(dir);
            if (v != null) variants.add(v);
        }
        return variants;
    }

    /** Map of skill name -> preferred-language version text (for builtin lookup). */
    public static Map<String, String> getPackagedBuiltinVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        String preference = getBuiltinSkillLanguagePreference();
        Map<String, BuiltinVariant> selected = new LinkedHashMap<>();
        for (BuiltinVariant v : iterPackagedBuiltinVariants()) {
            BuiltinVariant existing = selected.get(v.skillName());
            if (existing == null) {
                selected.put(v.skillName(), v);
            } else if (v.language().equals(preference) && !existing.language().equals(preference)) {
                selected.put(v.skillName(), v);
            }
        }
        for (BuiltinVariant v : selected.values()) {
            versions.put(v.skillName(), v.versionText());
        }
        return versions;
    }

    public static String getBuiltinSkillLanguagePreference() {
        Path settings = SkillStore.WORKING_DIR.resolve("settings.json");
        try {
            if (Files.isRegularFile(settings)) {
                Map<String, Object> cfg = SkillStore.readJson(settings, Map.of());
                Object lang = cfg.get("builtin_skill_language");
                if (lang != null) {
                    String l = String.valueOf(lang).trim().toLowerCase();
                    if (BUILTIN_SKILL_LANGUAGES.contains(l)) return l;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read builtin language preference: {}", e.getMessage());
        }
        return "en";
    }

    public static String resolvePoolBuiltinLanguage(String skillName, String existingLanguage) {
        String preference = getBuiltinSkillLanguagePreference();
        String candidate = existingLanguage != null && !existingLanguage.isEmpty()
                ? existingLanguage : preference;
        for (BuiltinVariant v : iterPackagedBuiltinVariants()) {
            if (v.skillName().equals(skillName) && v.language().equals(candidate)) return candidate;
        }
        // fall back to any available variant
        for (BuiltinVariant v : iterPackagedBuiltinVariants()) {
            if (v.skillName().equals(skillName)) return v.language();
        }
        return candidate;
    }

    // ------------------------------------------------------------------
    // Pool / workspace init + reconcile
    // ------------------------------------------------------------------

    public static boolean ensureSkillPoolInitialized() {
        Path poolDir = SkillStore.getSkillPoolDir();
        try {
            Files.createDirectories(poolDir);
        } catch (IOException e) {
            log.warn("Cannot create pool dir: {}", poolDir);
            return false;
        }
        if (Files.isRegularFile(SkillStore.getPoolSkillManifestPath())) {
            // heal: an existing manifest may predate builtin import (empty skills map)
            Map<String, Object> manifest = SkillStore.readPoolManifest();
            Map<String, Object> skills = SkillService.asMap(manifest.get("skills"));
            for (String name : getPackagedBuiltinVersions().keySet()) {
                if (!skills.containsKey(name)) {
                    reconcilePoolManifest();
                    break;
                }
            }
            return true;
        }
        reconcilePoolManifest();
        return true;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> reconcilePoolManifest() {
        Path manifestPath = SkillStore.getPoolSkillManifestPath();
        Path poolDir = SkillStore.getSkillPoolDir();
        try {
            Files.createDirectories(poolDir);
        } catch (IOException e) {
            throw new SkillsError("Cannot create pool dir: " + poolDir);
        }
        // packaged builtin names
        List<String> builtinNames = new ArrayList<>(getPackagedBuiltinVersions().keySet());
        Collections.sort(builtinNames);

        return SkillStore.mutateJson(manifestPath, SkillService.defaultPoolManifest(), payload -> {
            Map<String, Object> skills = SkillService.asMap(payload.get("skills"));
            // remove vanished customized entries
            Iterator<Map.Entry<String, Object>> it = skills.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                String name = e.getKey();
                Map<String, Object> entry = SkillService.asMap(e.getValue());
                String source = SkillService.str(entry.get("source"));
                if ("builtin".equals(source)) continue;
                if ("hub".equals(source)) continue;
                Path dir = SkillStore.resolvePoolSkillDir(name);
                if (dir == null || !Files.isRegularFile(dir.resolve("SKILL.md"))) {
                    it.remove();
                }
            }
            // import packaged builtins missing from the pool (the original does this at startup)
            String preference = getBuiltinSkillLanguagePreference();
            Map<String, BuiltinVariant> preferred = new LinkedHashMap<>();
            for (BuiltinVariant v : iterPackagedBuiltinVariants()) {
                BuiltinVariant existing = preferred.get(v.skillName());
                if (existing == null
                        || (v.language().equals(preference) && !existing.language().equals(preference))) {
                    preferred.put(v.skillName(), v);
                }
            }
            for (BuiltinVariant v : preferred.values()) {
                if (skills.containsKey(v.skillName())) continue;
                Path targetDir = SkillStore.safeSkillDir(poolDir, v.skillName());
                SkillStore.copySkillDir(v.dir(), targetDir);
                Map<String, Object> entry = SkillStore.buildSkillMetadata(v.skillName(), targetDir, "builtin", false);
                entry.put("external", false);
                entry.put("builtin_language", v.language());
                entry.put("builtin_source_name", v.skillName());
                skills.put(v.skillName(), entry);
            }
            // register orphan skill dirs
            if (Files.isDirectory(poolDir)) {
                try (var stream = Files.list(poolDir)) {
                    List<Path> dirs = stream.filter(Files::isDirectory).toList();
                    for (Path dir : dirs) {
                        String name = dir.getFileName().toString();
                        if (SkillStore.isIgnoredSkillEntry(name)) continue;
                        if (!Files.isRegularFile(dir.resolve("SKILL.md"))) continue;
                        if (!skills.containsKey(name)) {
                            Map<String, Object> entry = SkillStore.buildSkillMetadata(name, dir, "customized", false);
                            entry.put("external", false);
                            skills.put(name, entry);
                        }
                    }
                } catch (IOException ignored) {}
            }
            payload.put("builtin_skill_names", builtinNames);
            return payload;
        });
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> reconcileWorkspaceManifest(Path workspaceDir) {
        Path manifestPath = SkillStore.getWorkspaceSkillManifestPath(workspaceDir);
        Path skillsDir = SkillStore.getWorkspaceSkillsDir(workspaceDir);
        try {
            Files.createDirectories(workspaceDir);
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            throw new SkillsError("Cannot create workspace dirs: " + workspaceDir);
        }
        return SkillStore.mutateJson(manifestPath, SkillService.defaultWorkspaceManifest(), payload -> {
            Map<String, Object> skills = SkillService.asMap(payload.get("skills"));
            if (Files.isDirectory(skillsDir)) {
                try (var stream = Files.list(skillsDir)) {
                    List<Path> dirs = stream.filter(Files::isDirectory).toList();
                    for (Path dir : dirs) {
                        String name = dir.getFileName().toString();
                        if (SkillStore.isIgnoredSkillEntry(name)) continue;
                        if (!Files.isRegularFile(dir.resolve("SKILL.md"))) continue;
                        if (!skills.containsKey(name)) {
                            registerWorkspaceEntry(skills, name, dir);
                        }
                    }
                } catch (IOException ignored) {}
            }
            return payload;
        });
    }

    @SuppressWarnings("unchecked")
    private static void registerWorkspaceEntry(Map<String, Object> skills, String name, Path dir) {
        Map<String, Object> entry = SkillService.asMap(skills.get(name));
        String source = "customized";
        if (entry.containsKey("source")) {
            source = SkillService.str(entry.get("source"));
        } else if (getPackagedBuiltinVersions().containsKey(name)) {
            source = "builtin";
        }
        Map<String, Object> metadata = SkillStore.buildSkillMetadata(name, dir, source, false);
        Map<String, Object> wsEntry = new LinkedHashMap<>();
        wsEntry.put("enabled", entry.containsKey("enabled") ? entry.get("enabled") : false);
        wsEntry.put("channels", entry.get("channels") != null ? entry.get("channels") : List.of("all"));
        wsEntry.put("source", metadata.get("source"));
        wsEntry.put("installed_from", entry.get("installed_from") != null ? entry.get("installed_from") : "");
        wsEntry.put("config", entry.get("config") != null ? entry.get("config") : new LinkedHashMap<>());
        wsEntry.put("metadata", metadata);
        wsEntry.put("requirements", metadata.get("requirements"));
        wsEntry.put("updated_at", metadata.get("updated_at"));
        skills.put(name, wsEntry);
    }

    // ------------------------------------------------------------------
    // Workspaces
    // ------------------------------------------------------------------

    public static List<Map<String, String>> listWorkspaces() {
        return AgentStore.listWorkspaces();
    }

    public static Path workspaceDirForAgent(String agentId) {
        return AgentStore.workspaceDirForAgent(agentId);
    }

    // ------------------------------------------------------------------
    // Builtin import candidates / notice / update
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listBuiltinImportCandidates() {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        Map<String, Object> pool = SkillStore.readPoolManifest();
        Map<String, Object> poolSkills = SkillService.asMap(pool.get("skills"));
        for (BuiltinVariant v : iterPackagedBuiltinVariants()) {
            Map<String, Object> candidate = byName.computeIfAbsent(v.skillName(), n -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", n);
                c.put("languages", new LinkedHashMap<String, Object>());
                return c;
            });
            Map<String, Object> langs = SkillService.asMap(candidate.get("languages"));
            Map<String, Object> langSpec = new LinkedHashMap<>();
            langSpec.put("language", v.language());
            langSpec.put("description", v.description());
            langSpec.put("version_text", v.versionText());
            langSpec.put("source_name", v.skillName());
            Map<String, Object> poolEntry = SkillService.asMap(poolSkills.get(v.skillName()));
            String poolLang = SkillService.str(poolEntry.get("builtin_language")).toLowerCase();
            if (v.language().equals(poolLang)) {
                langSpec.put("status", "current");
            } else if (poolEntry.isEmpty()) {
                langSpec.put("status", "missing");
            } else {
                langSpec.put("status", "conflict");
            }
            langs.put(v.language(), langSpec);
            if (candidate.get("description") == null) candidate.put("description", v.description());
            if (candidate.get("version_text") == null) candidate.put("version_text", v.versionText());
            List<String> available = SkillService.toStringList(candidate.get("available_languages"));
            if (!available.contains(v.language())) available.add(v.language());
            candidate.put("available_languages", available);
        }
        List<Map<String, Object>> result = new ArrayList<>(byName.values());
        result.sort(Comparator.comparing(c -> SkillService.str(c.get("name"))));
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getPoolBuiltinUpdateNotice() {
        Map<String, Object> pool = SkillStore.readPoolManifest();
        Map<String, Object> poolSkills = SkillService.asMap(pool.get("skills"));
        Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        for (Map<String, Object> c : listBuiltinImportCandidates()) {
            candidates.put(SkillService.str(c.get("name")), c);
        }
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        List<Map<String, Object>> updated = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : candidates.entrySet()) {
            String name = e.getKey();
            Map<String, Object> candidate = e.getValue();
            Map<String, Object> poolEntry = SkillService.asMap(poolSkills.get(name));
            Map<String, Object> item = new LinkedHashMap<>(candidate);
            item.remove("languages");
            if (poolEntry.isEmpty()) {
                item.put("status", "missing");
                missing.add(item);
            } else {
                String poolSource = SkillService.str(poolEntry.get("source"));
                String poolVersion = SkillService.str(poolEntry.get("version_text"));
                String packagedVersion = SkillService.str(candidate.get("version_text"));
                item.put("current_version_text", poolVersion);
                item.put("current_source", poolSource);
                item.put("current_language", SkillService.str(poolEntry.get("builtin_language")));
                if (!"builtin".equals(poolSource)) {
                    item.put("status", "conflict");
                    updated.add(item);
                } else if (!packagedVersion.isEmpty() && !packagedVersion.equals(poolVersion)) {
                    item.put("status", "outdated");
                    updated.add(item);
                } else {
                    item.put("status", "current");
                    added.add(item);
                }
            }
        }
        for (String name : poolSkills.keySet()) {
            Map<String, Object> poolEntry = SkillService.asMap(poolSkills.get(name));
            if (!"builtin".equals(SkillService.str(poolEntry.get("source")))) continue;
            if (!candidates.containsKey(name)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("current_version_text", SkillService.str(poolEntry.get("version_text")));
                item.put("current_source", "builtin");
                removed.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        int total = added.size() + missing.size() + updated.size() + removed.size();
        result.put("total_changes", total);
        result.put("has_updates", total > 0);
        result.put("added", added);
        result.put("missing", missing);
        result.put("updated", updated);
        result.put("removed", removed);
        List<String> actionable = new ArrayList<>();
        for (Map<String, Object> item : missing) actionable.add(SkillService.str(item.get("name")));
        for (Map<String, Object> item : updated) actionable.add(SkillService.str(item.get("name")));
        result.put("actionable_skill_names", actionable);
        result.put("fingerprint", buildBuiltinNoticeFingerprint(poolSkills, candidates));
        return result;
    }

    private static String buildBuiltinNoticeFingerprint(Map<String, Object> poolSkills,
                                                        Map<String, Map<String, Object>> candidates) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> names = new ArrayList<>(candidates.keySet());
            Collections.sort(names);
            StringBuilder sb = new StringBuilder();
            for (String name : names) {
                Map<String, Object> poolEntry = SkillService.asMap(poolSkills.get(name));
                sb.append(name).append(":")
                        .append(SkillService.str(candidateVersion(candidates.get(name))))
                        .append(":")
                        .append(SkillService.str(poolEntry.get("version_text"))).append("|");
            }
            byte[] hash = digest.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String candidateVersion(Map<String, Object> candidate) {
        Object v = candidate.get("version_text");
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> importBuiltinSkills(List<Map<String, Object>> imports,
                                                         boolean overwriteConflicts) {
        Map<String, Object> pool = SkillStore.readPoolManifest();
        Map<String, Object> poolSkills = SkillService.asMap(pool.get("skills"));
        Map<String, BuiltinVariant> variants = new LinkedHashMap<>();
        for (BuiltinVariant v : iterPackagedBuiltinVariants()) {
            variants.put(v.skillName() + "|" + v.language(), v);
        }
        List<String> imported = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Set<String> done = new HashSet<>();

        for (Map<String, Object> req : imports) {
            String skillName = SkillService.str(req.get("skill_name")).trim();
            String language = SkillService.str(req.get("language")).trim();
            if (skillName.isEmpty()) continue;
            if (language.isEmpty()) language = getBuiltinSkillLanguagePreference();
            if (!BUILTIN_SKILL_LANGUAGES.contains(language)) continue;
            String key = skillName + "|" + language;
            BuiltinVariant v = variants.get(key);
            if (v == null) {
                // fall back to the other language
                String other = "en".equals(language) ? "zh" : "en";
                v = variants.get(skillName + "|" + other);
            }
            if (v == null) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("reason", "builtin_not_found");
                c.put("skill_name", skillName);
                conflicts.add(c);
                continue;
            }
            if (done.contains(skillName)) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("reason", "conflict");
                c.put("skill_name", skillName);
                c.put("suggested_name", SkillStore.suggestConflictName(skillName, new HashSet<>(poolSkills.keySet())));
                conflicts.add(c);
                continue;
            }
            done.add(skillName);
            Map<String, Object> poolEntry = SkillService.asMap(poolSkills.get(skillName));
            if (!poolEntry.isEmpty() && !"builtin".equals(SkillService.str(poolEntry.get("source")))) {
                if (!overwriteConflicts) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("reason", "conflict");
                    c.put("skill_name", skillName);
                    c.put("suggested_name", SkillStore.suggestConflictName(skillName, new HashSet<>(poolSkills.keySet())));
                    conflicts.add(c);
                    continue;
                }
            }
            boolean exists = !poolEntry.isEmpty();
            String oldVersion = SkillService.str(poolEntry.get("version_text"));
            String newVersion = v.versionText();
            if (exists && oldVersion.equals(newVersion)) {
                unchanged.add(skillName);
                continue;
            }
            // copy builtin content into pool
            Path poolDir = SkillStore.getSkillPoolDir();
            try {
                Files.createDirectories(poolDir);
            } catch (IOException e) {
                throw new SkillsError("Cannot create pool dir");
            }
            Path targetDir = SkillStore.safeSkillDir(poolDir, skillName);
            SkillStore.copySkillDir(v.dir(), targetDir);
            final String skillNameF = skillName;
            final BuiltinVariant variantF = v;
            SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(), payload -> {
                Map<String, Object> skills = SkillService.asMap(payload.get("skills"));
                Map<String, Object> entry = SkillStore.buildSkillMetadata(skillNameF, targetDir, "builtin", false);
                entry.put("external", false);
                entry.put("builtin_language", variantF.language());
                entry.put("builtin_source_name", skillNameF);
                skills.put(skillNameF, entry);
                return null;
            });
            if (exists) updated.add(skillName); else imported.add(skillName);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("updated", updated);
        result.put("unchanged", unchanged);
        result.put("conflicts", conflicts);
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> updateSingleBuiltin(String skillName, String language) {
        String lang = (language == null || language.isEmpty())
                ? getBuiltinSkillLanguagePreference() : language;
        BuiltinVariant selected = null;
        for (BuiltinVariant v : iterPackagedBuiltinVariants()) {
            if (v.skillName().equals(skillName)) {
                if (v.language().equals(lang)) { selected = v; break; }
                if (selected == null) selected = v;
            }
        }
        if (selected == null) {
            throw new SkillsError("Packaged builtin skill '" + skillName + "' not found");
        }
        Path poolDir = SkillStore.getSkillPoolDir();
        try {
            Files.createDirectories(poolDir);
        } catch (IOException e) {
            throw new SkillsError("Cannot create pool dir");
        }
        Path targetDir = SkillStore.safeSkillDir(poolDir, skillName);
        SkillStore.copySkillDir(selected.dir(), targetDir);
        final BuiltinVariant v = selected;
        SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(), payload -> {
            Map<String, Object> skills = SkillService.asMap(payload.get("skills"));
            Map<String, Object> entry = SkillStore.buildSkillMetadata(skillName, targetDir, "builtin", false);
            entry.put("external", false);
            entry.put("builtin_language", v.language());
            entry.put("builtin_source_name", skillName);
            skills.put(skillName, entry);
            return null;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", true);
        result.put("name", skillName);
        result.put("language", v.language());
        result.put("version_text", v.versionText());
        return result;
    }

    /** Sync status info per pool skill (used to build PoolSkillSpec). */
    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, Object>> getPoolBuiltinSyncStatus(Map<String, Object> poolSkills) {
        Map<String, Map<String, Object>> sync = new LinkedHashMap<>();
        Map<String, String> packaged = getPackagedBuiltinVersions();
        for (Map.Entry<String, Object> e : poolSkills.entrySet()) {
            String name = e.getKey();
            Map<String, Object> entry = SkillService.asMap(e.getValue());
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("sync_status", "");
            info.put("latest_version_text", "");
            info.put("available_languages", List.of());
            if ("builtin".equals(SkillService.str(entry.get("source")))) {
                String packagedVersion = packaged.getOrDefault(name, "");
                String poolVersion = SkillService.str(entry.get("version_text"));
                info.put("latest_version_text", packagedVersion);
                if (packagedVersion.isEmpty()) {
                    info.put("sync_status", "-");
                } else if (packagedVersion.equals(poolVersion)) {
                    info.put("sync_status", "synced");
                } else {
                    info.put("sync_status", "outdated");
                }
                List<String> langs = new ArrayList<>();
                for (BuiltinVariant v : iterPackagedBuiltinVariants()) {
                    if (v.skillName().equals(name) && !langs.contains(v.language())) langs.add(v.language());
                }
                info.put("available_languages", langs);
            }
            sync.put(name, info);
        }
        return sync;
    }

    public static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
