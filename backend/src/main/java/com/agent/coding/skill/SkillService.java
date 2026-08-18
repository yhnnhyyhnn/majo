package com.agent.coding.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Workspace-scoped skill lifecycle service.
 *
 * Owns editable skills inside one workspace: create, zip import, save
 * (edit/rename), enable/disable, channel routing, tags, config persistence
 * and file access. Treats {@code <workspace>/skills} as the source of truth
 * for skill content and {@code <workspace>/skill.json} for runtime state.
 *
 *. 
 */
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    public static final int MAX_TAGS = 8;
    public static final int MAX_TAG_LENGTH = 16;

    private final Path workspaceDir;

    public SkillService(Path workspaceDir) {
        this.workspaceDir = workspaceDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.workspaceDir);
        } catch (IOException e) {
            throw new SkillsError("Cannot create workspace dir: " + this.workspaceDir);
        }
    }

    public Path getWorkspaceDir() {
        return workspaceDir;
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    public Map<String, Object> readManifest() {
        return SkillStore.readWorkspaceManifest(workspaceDir);
    }

    /** Build SkillSpec list exactly like router _build_workspace_skill_specs. */
    @SuppressWarnings("unchecked")
    public List<SkillModels.SkillSpec> buildSkillSpecs() {
        Map<String, Object> manifest = readManifest();
        Map<String, Object> entries = asMap(manifest.get("skills"));
        Path skillRoot = SkillStore.getWorkspaceSkillsDir(workspaceDir);
        List<SkillModels.SkillSpec> specs = new ArrayList<>();
        List<String> names = new ArrayList<>(entries.keySet());
        Collections.sort(names);
        for (String skillName : names) {
            Map<String, Object> entry = asMap(entries.get(skillName));
            try {
                String source = entry.get("source") == null ? "customized" : String.valueOf(entry.get("source"));
                Path skillDir = skillRoot.resolve(skillName);
                Map<String, Object> skill = readSkillFromDir(skillDir, source);
                if (skill == null) continue;
                SkillModels.SkillSpec spec = new SkillModels.SkillSpec();
                spec.name = skillName;
                spec.description = str(skill.get("description"));
                spec.versionText = str(skill.get("version_text"));
                spec.content = str(skill.get("content"));
                spec.source = source;
                spec.tags = toStringList(entry.get("tags"));
                spec.enabled = bool(entry.get("enabled"), false);
                spec.channels = toStringListOrAll(entry.get("channels"));
                spec.config = asMap(entry.get("config"));
                spec.lastUpdated = SkillStore.getSkillMtime(skillDir);
                spec.installedFrom = str(entry.get("installed_from"));
                Object emoji = skill.get("emoji");
                spec.emoji = emoji == null ? null : String.valueOf(emoji);
                specs.add(spec);
            } catch (Exception e) {
                log.warn("Skipping workspace skill '{}': failed to build spec", skillName, e);
            }
        }
        return specs;
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    public String createSkill(String name, String content,
                              Map<String, Object> references,
                              Map<String, Object> scripts,
                              Map<String, Object> extraFiles,
                              Map<String, Object> config,
                              boolean enable,
                              String installedFrom,
                              String source) {
        validateSkillContent(content);
        String skillName = SkillStore.normalizeSkillDirName(name);
        Path skillRoot = SkillStore.getWorkspaceSkillsDir(workspaceDir);
        try {
            Files.createDirectories(skillRoot);
        } catch (IOException e) {
            throw new SkillsError("Cannot create skills dir: " + skillRoot);
        }
        Path skillDir = SkillStore.safeSkillDir(skillRoot, skillName);
        if (Files.exists(skillDir)) return null;

        Path stagedDir = null;
        try {
            stagedDir = stagedSkillDir(skillName);
            writeSkillToDir(stagedDir, content, references, scripts, extraFiles);
            SkillScanner.scanSkillDirOrRaise(stagedDir, skillName);
            SkillStore.copySkillDir(stagedDir, skillDir);
        } finally {
            if (stagedDir != null) SkillStore.deleteRecursively(stagedDir);
        }

        try {
            SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                    defaultWorkspaceManifest(),
                    payload -> {
                        registerWorkspaceSkillEntry(payload, skillName, skillDir,
                                enable, installedFrom, config, source);
                        return null;
                    });
        } catch (Exception exc) {
            SkillStore.deleteRecursively(skillDir);
            throw new SkillsError("Workspace skill files were created, but manifest update failed. File changes were rolled back.", exc);
        }
        return skillName;
    }

    // ------------------------------------------------------------------
    // Save (edit / rename / noop)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> saveSkill(String skillName, String content,
                                         String targetName, Map<String, Object> config,
                                         boolean overwrite) {
        validateSkillContent(content);
        String normalizedName;
        try {
            normalizedName = SkillStore.normalizeSkillDirName(skillName);
        } catch (SkillsError e) {
            return result(false, "not_found");
        }
        String finalName;
        try {
            finalName = SkillStore.normalizeSkillDirName(targetName == null ? skillName : targetName);
        } catch (SkillsError e) {
            return result(false, "not_found");
        }
        Map<String, Object> manifest = readManifest();
        Map<String, Object> oldEntry = asMap(asMap(manifest.get("skills")).get(normalizedName));
        if (oldEntry == null) return result(false, "not_found");

        if (finalName.equals(normalizedName)) {
            return saveSkillInPlace(normalizedName, content, config, oldEntry);
        }

        Path skillRoot = SkillStore.getWorkspaceSkillsDir(workspaceDir);
        Path targetDir = SkillStore.safeSkillDir(skillRoot, finalName);
        if (Files.exists(targetDir) && !overwrite) {
            Set<String> existing = existingSkillNames(skillRoot);
            Map<String, Object> r = result(false, "conflict");
            r.put("suggested_name", SkillStore.suggestConflictName(finalName, existing));
            return r;
        }
        return saveSkillAsRename(normalizedName, finalName, content, config, oldEntry);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> saveSkillInPlace(String skillName, String content,
                                                 Map<String, Object> config,
                                                 Map<String, Object> oldEntry) {
        Map<String, Object> newConfig = config != null ? config : asMap(oldEntry.get("config"));
        Path skillRoot = SkillStore.getWorkspaceSkillsDir(workspaceDir);
        try {
            Files.createDirectories(skillRoot);
        } catch (IOException e) {
            throw new SkillsError("Cannot create skills dir: " + skillRoot);
        }
        Path skillDir = SkillStore.safeSkillDir(skillRoot, skillName);

        String oldMd = "";
        try {
            if (Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                oldMd = SkillStore.readTextFile(skillDir.resolve("SKILL.md"));
            }
        } catch (IOException ignored) {}
        boolean contentChanged = !content.equals(oldMd);
        boolean configSame = newConfig.equals(asMap(oldEntry.get("config")));
        if (!contentChanged && configSame) {
            Map<String, Object> r = result(true, null);
            r.put("mode", "noop");
            r.put("name", skillName);
            return r;
        }

        if (contentChanged) {
            Path stagedDir = null;
            try {
                stagedDir = stagedSkillDir(skillName);
                if (Files.exists(skillDir)) SkillStore.copySkillDir(skillDir, stagedDir);
                Files.write(stagedDir.resolve("SKILL.md"), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                SkillScanner.scanSkillDirOrRaise(stagedDir, skillName);
            } catch (IOException e) {
                throw new SkillsError("Failed to stage SKILL.md for scan: " + skillName, e);
            } finally {
                if (stagedDir != null) SkillStore.deleteRecursively(stagedDir);
            }
            try {
                Files.write(skillDir.resolve("SKILL.md"), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new SkillsError("Failed to write SKILL.md: " + skillDir);
            }
        }
        String source = contentChanged ? "customized" : str(oldEntry.get("source"), "customized");
        Map<String, Object> metadata = SkillStore.buildSkillMetadata(skillName, skillDir, source, false);

        SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                defaultWorkspaceManifest(),
                payload -> {
                    Map<String, Object> skills = ensureSkills(payload);
                    Map<String, Object> currentEntry = asMap(skills.get(skillName));
                    Map<String, Object> nextEntry = new LinkedHashMap<>();
                    nextEntry.put("enabled", bool(currentEntry.get("enabled"), false));
                    nextEntry.put("channels", currentEntry.get("channels") != null ? currentEntry.get("channels") : List.of("all"));
                    nextEntry.put("source", metadata.get("source"));
                    nextEntry.put("installed_from", str(currentEntry.get("installed_from")));
                    nextEntry.put("config", newConfig);
                    nextEntry.put("metadata", metadata);
                    nextEntry.put("requirements", metadata.get("requirements"));
                    nextEntry.put("updated_at", metadata.get("updated_at"));
                    if (currentEntry.get("tags") != null) nextEntry.put("tags", currentEntry.get("tags"));
                    skills.put(skillName, nextEntry);
                    return null;
                });

        Map<String, Object> r = result(true, null);
        r.put("mode", "edit");
        r.put("name", skillName);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> saveSkillAsRename(String skillName, String finalName,
                                                  String content, Map<String, Object> config,
                                                  Map<String, Object> oldEntry) {
        Path skillRoot = SkillStore.getWorkspaceSkillsDir(workspaceDir);
        Path targetDir = SkillStore.safeSkillDir(skillRoot, finalName);
        Path oldDir = SkillStore.safeSkillDir(skillRoot, skillName);

        Path stagedDir = null;
        try {
            stagedDir = stagedSkillDir(finalName);
            SkillStore.copySkillDir(oldDir, stagedDir);
            Files.write(stagedDir.resolve("SKILL.md"), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SkillScanner.scanSkillDirOrRaise(stagedDir, finalName);
            SkillStore.copySkillDir(stagedDir, targetDir);
        } catch (IOException e) {
            throw new SkillsError("Failed during rename save: " + e.getMessage());
        } finally {
            if (stagedDir != null) SkillStore.deleteRecursively(stagedDir);
        }

        Map<String, Object> oldConfig = config != null ? config : asMap(oldEntry.get("config"));
        Object oldChannels = oldEntry.get("channels");
        Map<String, Object> metadata = SkillStore.buildSkillMetadata(finalName, targetDir, "customized", false);

        SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                defaultWorkspaceManifest(),
                payload -> {
                    Map<String, Object> skills = ensureSkills(payload);
                    Map<String, Object> currentEntry = asMap(skills.get(skillName));
                    Map<String, Object> nextEntry = new LinkedHashMap<>();
                    nextEntry.put("enabled", bool(currentEntry.get("enabled"), false));
                    nextEntry.put("channels", currentEntry.get("channels") != null ? currentEntry.get("channels") : oldChannels);
                    nextEntry.put("source", metadata.get("source"));
                    nextEntry.put("installed_from", str(currentEntry.get("installed_from")));
                    nextEntry.put("config", oldConfig);
                    nextEntry.put("metadata", metadata);
                    nextEntry.put("requirements", metadata.get("requirements"));
                    nextEntry.put("updated_at", metadata.get("updated_at"));
                    if (currentEntry.get("tags") != null) nextEntry.put("tags", currentEntry.get("tags"));
                    skills.put(finalName, nextEntry);
                    skills.remove(skillName);
                    return null;
                });

        if (Files.exists(oldDir)) SkillStore.deleteRecursively(oldDir);

        Map<String, Object> r = result(true, null);
        r.put("mode", "rename");
        r.put("name", finalName);
        return r;
    }

    // ------------------------------------------------------------------
    // Zip import
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> importFromZip(byte[] data, boolean enable,
                                             String targetName, Map<String, String> renameMap) {
        Path skillRoot = SkillStore.getWorkspaceSkillsDir(workspaceDir);
        try {
            Files.createDirectories(skillRoot);
        } catch (IOException e) {
            throw new SkillsError("Cannot create skills dir: " + skillRoot);
        }
        List<Object[]> found = SkillStore.extractZipSkills(data); // [dir, name]
        Map<String, String> renames = renameMap != null ? renameMap : Map.of();
        try {
            String normalizedTarget = targetName == null ? "" : targetName.trim();
            if (!normalizedTarget.isEmpty()) {
                normalizedTarget = SkillStore.normalizeSkillDirName(normalizedTarget);
                if (found.size() != 1) {
                    throw new SkillsError("target_name is only supported for single-skill zip imports");
                }
                found = List.<Object[]>of(new Object[]{found.get(0)[0], normalizedTarget});
            }
            List<Object[]> normalizedFound = new ArrayList<>();
            for (Object[] item : found) {
                Path d = (Path) item[0];
                String n = (String) item[1];
                normalizedFound.add(new Object[]{d, SkillStore.normalizeSkillDirName(renames.getOrDefault(n, n))});
            }
            Set<String> existingOnDisk = existingSkillNames(skillRoot);
            List<Map<String, Object>> conflicts = new ArrayList<>();
            List<Object[]> planned = new ArrayList<>();
            Set<String> seenNames = new HashSet<>();
            for (Object[] item : normalizedFound) {
                Path skillDir = (Path) item[0];
                String skillName = (String) item[1];
                try {
                    validateSkillContent(SkillStore.readTextFile(skillDir.resolve("SKILL.md")));
                } catch (IOException e) {
                    throw new SkillsError("Failed to read SKILL.md in zip");
                }
                SkillScanner.scanSkillDirOrRaise(skillDir, skillName);
                if (seenNames.contains(skillName)) {
                    conflicts.add(buildImportConflict(skillName, existingOnDisk));
                    continue;
                }
                seenNames.add(skillName);
                if (Files.exists(skillRoot.resolve(skillName))) {
                    conflicts.add(buildImportConflict(skillName, existingOnDisk));
                    continue;
                }
                planned.add(item);
            }
            if (!conflicts.isEmpty()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("imported", new ArrayList<>());
                r.put("count", 0);
                r.put("enabled", false);
                r.put("conflicts", conflicts);
                return r;
            }
            List<String> imported = new ArrayList<>();
            for (Object[] item : planned) {
                Path skillDir = (Path) item[0];
                String skillName = (String) item[1];
                if (SkillStore.importSkillDir(skillDir, skillRoot, skillName)) imported.add(skillName);
            }
            if (!imported.isEmpty()) {
                SkillRegistry.reconcileWorkspaceManifest(workspaceDir);
                SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                        defaultWorkspaceManifest(),
                        payload -> {
                            Map<String, Object> skills = ensureSkills(payload);
                            for (String name : imported) {
                                Map<String, Object> entry = asMap(skills.get(name));
                                if (entry != null && !entry.isEmpty()) entry.put("installed_from", "zip");
                            }
                            return null;
                        });
                if (enable) {
                    for (String name : imported) enableSkill(name, null);
                }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("imported", imported);
            r.put("count", imported.size());
            r.put("enabled", enable && !imported.isEmpty());
            r.put("conflicts", conflicts);
            return r;
        } finally {
            // extracted tmp dirs cleaned by caller (zip data already in memory)
        }
    }

    // ------------------------------------------------------------------
    // Enable / disable / channels / tags / delete
    // ------------------------------------------------------------------

    public Map<String, Object> enableSkill(String name, List<String> targetWorkspaces) {
        String skillName;
        try {
            skillName = SkillStore.normalizeSkillDirName(name);
        } catch (SkillsError e) {
            return enableResult(false, List.of(), List.of(workspaceDir.getFileName().toString()), "not_found");
        }
        if (targetWorkspaces != null && !targetWorkspaces.isEmpty()
                && !targetWorkspaces.contains(workspaceDir.getFileName().toString())) {
            return enableResult(false, List.of(), targetWorkspaces, "workspace_mismatch");
        }
        Path manifestPath = SkillStore.getWorkspaceSkillManifestPath(workspaceDir);
        Path skillDir = SkillStore.safeSkillDir(SkillStore.getWorkspaceSkillsDir(workspaceDir), skillName);
        if (!Files.exists(skillDir)) {
            return enableResult(false, List.of(), List.of(workspaceDir.getFileName().toString()), "not_found");
        }
        SkillScanner.scanSkillDirOrRaise(skillDir, skillName);

        Boolean updated = SkillStore.mutateJson(manifestPath, defaultWorkspaceManifest(),
                payload -> {
                    Map<String, Object> entry = asMap(ensureSkills(payload).get(skillName));
                    if (entry == null || entry.isEmpty()) return false;
                    entry.put("enabled", true);
                    return true;
                });
        if (!updated) {
            return enableResult(false, List.of(), List.of(workspaceDir.getFileName().toString()), "not_found");
        }
        return enableResult(true, List.of(workspaceDir.getFileName().toString()), List.of(), null);
    }

    private Map<String, Object> enableResult(boolean success, List<String> updated,
                                             List<String> failed, String reason) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", success);
        r.put("updated_workspaces", updated);
        r.put("failed", failed);
        r.put("reason", reason);
        return r;
    }

    public Map<String, Object> disableSkill(String name) {
        String skillName;
        try {
            skillName = SkillStore.normalizeSkillDirName(name);
        } catch (SkillsError e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("updated_workspaces", List.of());
            return r;
        }
        Boolean updated = SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                defaultWorkspaceManifest(),
                payload -> {
                    Map<String, Object> entry = asMap(ensureSkills(payload).get(skillName));
                    if (entry == null || entry.isEmpty()) return false;
                    entry.put("enabled", false);
                    return true;
                });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", updated);
        r.put("updated_workspaces", updated ? List.of(workspaceDir.getFileName().toString()) : List.of());
        return r;
    }

    public boolean setSkillChannels(String name, List<String> channels) {
        String skillName;
        try {
            skillName = SkillStore.normalizeSkillDirName(name);
        } catch (SkillsError e) {
            return false;
        }
        List<String> normalized = channels != null ? channels : List.of("all");
        return SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                defaultWorkspaceManifest(),
                payload -> {
                    Map<String, Object> entry = asMap(ensureSkills(payload).get(skillName));
                    if (entry == null || entry.isEmpty()) return false;
                    entry.put("channels", normalized);
                    return true;
                });
    }

    public boolean setSkillTags(String name, List<String> tags) {
        String skillName;
        try {
            skillName = SkillStore.normalizeSkillDirName(name);
        } catch (SkillsError e) {
            return false;
        }
        List<String> normalized = tags != null ? tags : List.of();
        return SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                defaultWorkspaceManifest(),
                payload -> {
                    Map<String, Object> entry = asMap(ensureSkills(payload).get(skillName));
                    if (entry == null || entry.isEmpty()) return false;
                    entry.put("tags", normalized);
                    return true;
                });
    }

    /** Workspace skills can only be deleted when disabled. */
    @SuppressWarnings("unchecked")
    public boolean deleteSkill(String name) {
        String skillName;
        try {
            skillName = SkillStore.normalizeSkillDirName(name);
        } catch (SkillsError e) {
            return false;
        }
        Map<String, Object> manifest = readManifest();
        Map<String, Object> entry = asMap(asMap(manifest.get("skills")).get(skillName));
        if (entry == null || entry.isEmpty() || bool(entry.get("enabled"), false)) return false;

        Path skillDir = SkillStore.safeSkillDir(SkillStore.getWorkspaceSkillsDir(workspaceDir), skillName);
        if (Files.exists(skillDir)) SkillStore.deleteRecursively(skillDir);

        try {
            SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                    defaultWorkspaceManifest(),
                    payload -> {
                        ensureSkills(payload).remove(skillName);
                        return null;
                    });
        } catch (Exception exc) {
            throw new SkillsError("Workspace skill files were deleted, but manifest update failed.", exc);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // File access
    // ------------------------------------------------------------------

    public String loadSkillFile(String skillName, String filePath) {
        String normalized = filePath.replace("\\", "/");
        if (normalized.contains("..") || normalized.startsWith("/")
                || !(normalized.startsWith("references/") || normalized.startsWith("scripts/"))) {
            return null;
        }
        String n;
        try {
            n = SkillStore.normalizeSkillDirName(skillName);
        } catch (SkillsError e) {
            return null;
        }
        Path baseDir = SkillStore.safeSkillDir(SkillStore.getWorkspaceSkillsDir(workspaceDir), n);
        if (!asMap(readManifest().get("skills")).containsKey(n)) return null;
        if (!Files.exists(baseDir)) return null;
        Path fullPath = baseDir.resolve(normalized).toAbsolutePath().normalize();
        if (!fullPath.startsWith(baseDir) || !Files.isRegularFile(fullPath)) return null;
        try {
            return SkillStore.readTextFile(fullPath);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void registerWorkspaceSkillEntry(Map<String, Object> payload, String skillName,
                                             Path skillDir, boolean enable, String installedFrom,
                                             Map<String, Object> config, String source) {
        Map<String, Object> skills = ensureSkills(payload);
        Map<String, Object> entry = asMap(skills.get(skillName));
        String resolvedSource;
        if (source != null) resolvedSource = source;
        else if (entry.containsKey("source")) resolvedSource = str(entry.get("source"));
        else if (SkillRegistry.getPackagedBuiltinVersions().containsKey(skillName)) resolvedSource = "builtin";
        else resolvedSource = "customized";

        Map<String, Object> metadata = SkillStore.buildSkillMetadata(skillName, skillDir, resolvedSource, false);
        Map<String, Object> wsEntry = new LinkedHashMap<>();
        wsEntry.put("enabled", entry.containsKey("enabled") ? entry.get("enabled") : enable);
        wsEntry.put("channels", entry.get("channels") != null ? entry.get("channels") : List.of("all"));
        wsEntry.put("source", metadata.get("source"));
        wsEntry.put("installed_from", !installedFrom.isEmpty() ? installedFrom : str(entry.get("installed_from")));
        wsEntry.put("config", config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>(asMap(entry.get("config"))));
        wsEntry.put("metadata", metadata);
        wsEntry.put("requirements", metadata.get("requirements"));
        wsEntry.put("updated_at", metadata.get("updated_at"));
        skills.put(skillName, wsEntry);
    }

    public static void validateSkillContent(String content) {
        if (content == null) throw new SkillsError("SKILL.md frontmatter is not valid YAML");
        Map<String, Object> post;
        try {
            post = SkillStore.parseFrontmatter(content);
        } catch (Exception e) {
            throw new SkillsError("SKILL.md frontmatter is not valid YAML: " + e.getMessage());
        }
        String skillName = str(post.get("name")).trim();
        String skillDescription = str(post.get("description")).trim();
        if (skillName.isEmpty() || skillDescription.isEmpty()) {
            throw new SkillsError("SKILL.md must include non-empty frontmatter name and description");
        }
        Object metadata = post.get("metadata");
        if (metadata != null && !(metadata instanceof Map)) {
            throw new SkillsError("SKILL.md frontmatter 'metadata' must be a dict");
        }
    }

    /** Create a temp staging dir whose child is the skill name. */
    public static Path stagedSkillDir(String skillName) {
        try {
            Path tempRoot = Files.createTempDirectory("majo_skill_stage_" + skillName + "_");
            return tempRoot.resolve(skillName);
        } catch (IOException e) {
            throw new SkillsError("Cannot create staging dir");
        }
    }

    public static void writeSkillToDir(Path skillDir, String content,
                                       Map<String, Object> references,
                                       Map<String, Object> scripts,
                                       Map<String, Object> extraFiles) {
        try {
            Files.createDirectories(skillDir);
            Files.write(skillDir.resolve("SKILL.md"), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SkillStore.createFilesFromTree(skillDir, extraFiles == null ? Map.of() : extraFiles);
            if (references != null && !references.isEmpty()) {
                Path refDir = skillDir.resolve("references");
                Files.createDirectories(refDir);
                SkillStore.createFilesFromTree(refDir, references);
            }
            if (scripts != null && !scripts.isEmpty()) {
                Path scriptDir = skillDir.resolve("scripts");
                Files.createDirectories(scriptDir);
                SkillStore.createFilesFromTree(scriptDir, scripts);
            }
        } catch (IOException e) {
            throw new SkillsError("Failed to write skill to dir: " + e.getMessage());
        }
    }

    /** Read a skill from a directory into a map with SkillInfo keys. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readSkillFromDir(Path skillDir, String source) {
        if (!Files.isDirectory(skillDir)) return null;
        Path skillMd = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) return null;
        try {
            String content = SkillStore.readTextFile(skillMd);
            String description = "";
            String emoji = null;
            Map<String, Object> post = new HashMap<>();
            try {
                post = SkillStore.parseFrontmatter(content);
                description = str(post.get("description"));
                emoji = SkillStore.extractEmoji(post);
            } catch (Exception ignored) {}
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("name", skillDir.getFileName().toString());
            skill.put("description", description);
            skill.put("version_text", SkillStore.extractVersion(post));
            skill.put("content", content);
            skill.put("source", source);
            skill.put("references", SkillStore.directoryTree(skillDir.resolve("references")));
            skill.put("scripts", SkillStore.directoryTree(skillDir.resolve("scripts")));
            skill.put("emoji", emoji == null ? "" : emoji);
            return skill;
        } catch (Exception exc) {
            log.error("Failed to read skill {}: {}", skillDir, exc.getMessage());
            return null;
        }
    }

    public static Map<String, Object> defaultWorkspaceManifest() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", "workspace-skill-manifest.v1");
        m.put("version", 0);
        m.put("skills", new LinkedHashMap<String, Object>());
        return m;
    }

    public static Map<String, Object> defaultPoolManifest() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", "skill-pool-manifest.v1");
        m.put("version", 0);
        m.put("skills", new LinkedHashMap<String, Object>());
        m.put("builtin_skill_names", new ArrayList<String>());
        return m;
    }

    public static Map<String, Object> buildImportConflict(String skillName, Set<String> existingNames) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("reason", "conflict");
        c.put("skill_name", skillName);
        c.put("suggested_name", SkillStore.suggestConflictName(skillName, existingNames));
        return c;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ensureSkills(Map<String, Object> payload) {
        Object skills = payload.get("skills");
        if (!(skills instanceof Map)) {
            Map<String, Object> s = new LinkedHashMap<>();
            payload.put("skills", s);
            return s;
        }
        return (Map<String, Object>) skills;
    }

    public static Set<String> existingSkillNames(Path skillRoot) {
        Set<String> names = new HashSet<>();
        if (Files.isDirectory(skillRoot)) {
            try (var stream = Files.list(skillRoot)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> !SkillStore.isIgnoredSkillEntry(p.getFileName().toString()))
                        .forEach(p -> names.add(p.getFileName().toString()));
            } catch (IOException ignored) {}
        }
        return names;
    }

    public static Map<String, Object> result(boolean success, String reason) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", success);
        if (reason != null) r.put("reason", reason);
        return r;
    }

    public static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    public static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }
    public static boolean bool(Object o, boolean def) { return o instanceof Boolean b ? b : def; }

    @SuppressWarnings("unchecked")
    public static List<String> toStringList(Object o) {
        if (o instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object item : (List<Object>) o) if (item != null) out.add(String.valueOf(item));
            return out;
        }
        return new ArrayList<>();
    }

    public static List<String> toStringListOrAll(Object o) {
        List<String> list = toStringList(o);
        return list.isEmpty() ? new ArrayList<>(List.of("all")) : list;
    }
}
