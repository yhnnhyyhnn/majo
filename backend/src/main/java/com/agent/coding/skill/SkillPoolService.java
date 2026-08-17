package com.agent.coding.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Shared skill-pool lifecycle service.
 *
 * Manages reusable skills in the local shared pool {@code WORKING_DIR/skill_pool}:
 * create, zip import, save (edit/rename), delete, tags, auto-update, upload from
 * workspace, download to workspaces (with conflict preflight), and builtin sync.
 *
 *. 
 */
public class SkillPoolService {

    private static final Logger log = LoggerFactory.getLogger(SkillPoolService.class);

    public SkillPoolService() {
        SkillRegistry.ensureSkillPoolInitialized();
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public List<SkillModels.PoolSkillSpec> buildPoolSkillSpecs() {
        Map<String, Object> manifest = SkillStore.readPoolManifest();
        Map<String, Object> entries = SkillService.asMap(manifest.get("skills"));
        Path poolDir = SkillStore.getSkillPoolDir();
        Map<String, Map<String, Object>> syncInfo = SkillRegistry.getPoolBuiltinSyncStatus(entries);
        List<SkillModels.PoolSkillSpec> specs = new ArrayList<>();
        List<String> names = new ArrayList<>(entries.keySet());
        Collections.sort(names);
        for (String skillName : names) {
            Map<String, Object> entry = SkillService.asMap(entries.get(skillName));
            try {
                String source = SkillService.str(entry.get("source"), "customized");
                Path skillDir = SkillStore.resolvePoolSkillDir(skillName);
                if (skillDir == null) skillDir = poolDir.resolve(skillName);
                Map<String, Object> skill = SkillService.readSkillFromDir(skillDir, source);
                if (skill == null) continue;
                Map<String, Object> info = SkillService.asMap(syncInfo.get(skillName));
                boolean isExternal = SkillService.bool(entry.get("external"), false);
                SkillModels.PoolSkillSpec spec = new SkillModels.PoolSkillSpec();
                spec.name = skillName;
                spec.description = SkillService.str(skill.get("description"));
                spec.content = SkillService.str(skill.get("content"));
                spec.source = source;
                spec.protectedFlag = SkillService.bool(entry.get("protected"), false);
                spec.external = isExternal;
                spec.externalPath = isExternal ? skillDir.toString() : "";
                spec.versionText = SkillService.str(entry.get("version_text"));
                spec.commitText = SkillService.str(entry.get("commit_text"));
                spec.syncStatus = SkillService.str(info.get("sync_status"));
                spec.latestVersionText = SkillService.str(info.get("latest_version_text"));
                spec.builtinLanguage = SkillService.str(entry.get("builtin_language"));
                List<String> availableLangs = new ArrayList<>();
                for (Object o : SkillService.toStringList(info.get("available_languages"))) {
                    String l = String.valueOf(o);
                    if (!l.isEmpty()) availableLangs.add(l);
                }
                if (availableLangs.isEmpty()) availableLangs = SkillService.toStringList(entry.get("available_builtin_languages"));
                spec.availableBuiltinLanguages = availableLangs;
                spec.tags = SkillService.toStringList(entry.get("tags"));
                spec.config = SkillService.asMap(entry.get("config"));
                spec.lastUpdated = SkillStore.getSkillMtime(skillDir);
                spec.installedFrom = SkillService.str(entry.get("installed_from"));
                spec.autoUpdate = SkillService.bool(entry.get("auto_update"), false);
                Object targets = entry.get("auto_update_targets");
                spec.autoUpdateTargets = targets instanceof List ? SkillService.toStringList(targets) : null;
                Object emoji = skill.get("emoji");
                spec.emoji = emoji == null ? null : String.valueOf(emoji);
                specs.add(spec);
            } catch (Exception e) {
                log.warn("Skipping pool skill '{}': failed to build spec", skillName, e);
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
                              Map<String, Object> config,
                              String installedFrom) {
        SkillService.validateSkillContent(content);
        String skillName = SkillStore.normalizeSkillDirName(name);
        Path poolDir = SkillStore.getSkillPoolDir();
        try {
            Files.createDirectories(poolDir);
        } catch (IOException e) {
            throw new SkillsError("Cannot create pool dir: " + poolDir);
        }
        Path skillDir = SkillStore.safeSkillDir(poolDir, skillName);
        if (Files.exists(skillDir)) return null;

        Path stagedDir = null;
        try {
            stagedDir = SkillService.stagedSkillDir(skillName);
            SkillService.writeSkillToDir(stagedDir, content, references, scripts, null);
            SkillScanner.scanSkillDirOrRaise(stagedDir, skillName);
            SkillStore.copySkillDir(stagedDir, skillDir);
        } finally {
            if (stagedDir != null) SkillStore.deleteRecursively(stagedDir);
        }

        try {
            SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(),
                    payload -> {
                        registerPoolSkillEntry(payload, skillName, skillDir, "customized", false,
                                installedFrom, config, null, null);
                        return null;
                    });
        } catch (Exception exc) {
            SkillStore.deleteRecursively(skillDir);
            throw new SkillsError("Skill pool files were created, but manifest update failed. File changes were rolled back.", exc);
        }
        return skillName;
    }

    // ------------------------------------------------------------------
    // Zip import
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> importFromZip(byte[] data, String targetName, Map<String, String> renameMap) {
        Path poolDir = SkillStore.getSkillPoolDir();
        try {
            Files.createDirectories(poolDir);
        } catch (IOException e) {
            throw new SkillsError("Cannot create pool dir: " + poolDir);
        }
        List<Object[]> found = SkillStore.extractZipSkills(data);
        Map<String, String> renames = renameMap != null ? renameMap : Map.of();
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
        Map<String, Object> manifest = SkillStore.readPoolManifest();
        Set<String> existingPoolNames = new HashSet<>(SkillService.asMap(manifest.get("skills")).keySet());
        if (Files.isDirectory(poolDir)) {
            try (var stream = Files.list(poolDir)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> !SkillStore.isIgnoredSkillEntry(p.getFileName().toString()))
                        .forEach(p -> existingPoolNames.add(p.getFileName().toString()));
            } catch (IOException ignored) {}
        }
        for (Object[] item : normalizedFound) {
            Path skillDir = (Path) item[0];
            String skillName = (String) item[1];
            try {
                SkillService.validateSkillContent(SkillStore.readTextFile(skillDir.resolve("SKILL.md")));
            } catch (IOException e) {
                throw new SkillsError("Failed to read SKILL.md in zip");
            }
            SkillScanner.scanSkillDirOrRaise(skillDir, skillName);
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<Object[]> planned = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (Object[] item : normalizedFound) {
            Path skillDir = (Path) item[0];
            String skillName = (String) item[1];
            if (seenNames.contains(skillName)) {
                conflicts.add(SkillService.buildImportConflict(skillName, existingPoolNames));
                continue;
            }
            seenNames.add(skillName);
            boolean occupied = SkillService.asMap(manifest.get("skills")).containsKey(skillName)
                    || Files.exists(poolDir.resolve(skillName));
            if (occupied) {
                conflicts.add(SkillService.buildImportConflict(skillName, existingPoolNames));
                continue;
            }
            planned.add(item);
        }
        if (!conflicts.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("imported", new ArrayList<>());
            r.put("count", 0);
            r.put("conflicts", conflicts);
            return r;
        }
        List<String> imported = new ArrayList<>();
        for (Object[] item : planned) {
            Path skillDir = (Path) item[0];
            String skillName = (String) item[1];
            if (SkillStore.importSkillDir(skillDir, poolDir, skillName)) imported.add(skillName);
        }
        if (!imported.isEmpty()) {
            SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(),
                    payload -> {
                        for (String name : imported) {
                            registerPoolSkillEntry(payload, name, poolDir.resolve(name),
                                    "customized", false, "zip", null, null, null);
                        }
                        return null;
                    });
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("imported", imported);
        r.put("count", imported.size());
        r.put("conflicts", conflicts);
        return r;
    }

    // ------------------------------------------------------------------
    // Delete / tags / auto-update
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public boolean deleteSkill(String name) {
        String skillName;
        try {
            skillName = SkillStore.normalizeSkillDirName(name);
        } catch (SkillsError e) {
            return false;
        }
        Map<String, Object> manifest = SkillStore.readPoolManifest();
        if (SkillService.asMap(manifest.get("skills")).get(skillName) == null) return false;

        Path skillDir = SkillStore.resolvePoolSkillDir(skillName);
        if (skillDir == null) skillDir = SkillStore.safeSkillDir(SkillStore.getSkillPoolDir(), skillName);
        if (Files.exists(skillDir)) SkillStore.deleteRecursively(skillDir);

        try {
            SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(),
                    payload -> {
                        SkillService.asMap(payload.get("skills")).remove(skillName);
                        return null;
                    });
        } catch (Exception exc) {
            throw new SkillsError("Skill pool files were deleted, but manifest update failed.", exc);
        }
        return true;
    }

    public boolean setPoolSkillTags(String name, List<String> tags) {
        String skillName;
        try {
            skillName = SkillStore.normalizeSkillDirName(name);
        } catch (SkillsError e) {
            return false;
        }
        List<String> normalized = tags != null ? tags : List.of();
        return SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(),
                payload -> {
                    Map<String, Object> entry = SkillService.asMap(SkillService.asMap(payload.get("skills")).get(skillName));
                    if (entry == null || entry.isEmpty()) return false;
                    entry.put("tags", normalized);
                    return true;
                });
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> setSkillAutoUpdate(String name, boolean enabled, List<String> targets) {
        String skillName;
        try {
            skillName = SkillStore.normalizeSkillDirName(name);
        } catch (SkillsError e) {
            return null;
        }
        List<String> normalizedTargets = new ArrayList<>();
        if (targets != null) for (Object t : targets) normalizedTargets.add(String.valueOf(t));
        Boolean updated = SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(),
                payload -> {
                    Map<String, Object> entry = SkillService.asMap(SkillService.asMap(payload.get("skills")).get(skillName));
                    if (entry == null || entry.isEmpty()) return false;
                    entry.put("auto_update", enabled);
                    if (!normalizedTargets.isEmpty()) entry.put("auto_update_targets", normalizedTargets);
                    else entry.remove("auto_update_targets");
                    if (enabled) entry.remove("auto_update_synced_hash");
                    return true;
                });
        if (!updated) return null;
        if (enabled) return runPoolAutoUpdateSync(skillName);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("synced", new ArrayList<>());
        r.put("failed", new ArrayList<>());
        r.put("checked", 0);
        return r;
    }

    // ------------------------------------------------------------------
    // Save (edit / rename / noop)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> savePoolSkill(String skillName, String content,
                                             String targetName, Map<String, Object> config,
                                             boolean overwrite) {
        SkillService.validateSkillContent(content);
        String normalizedName;
        try {
            normalizedName = SkillStore.normalizeSkillDirName(skillName);
        } catch (SkillsError e) {
            return SkillService.result(false, "not_found");
        }
        String finalName;
        try {
            finalName = SkillStore.normalizeSkillDirName(targetName == null ? skillName : targetName);
        } catch (SkillsError e) {
            return SkillService.result(false, "not_found");
        }
        Map<String, Object> manifest = SkillStore.readPoolManifest();
        Map<String, Object> oldEntry = SkillService.asMap(SkillService.asMap(manifest.get("skills")).get(normalizedName));
        if (oldEntry == null || oldEntry.isEmpty()) return SkillService.result(false, "not_found");

        if (finalName.equals(normalizedName)) {
            return savePoolSkillInPlace(normalizedName, content, config, oldEntry);
        }
        Path poolDir = SkillStore.getSkillPoolDir();
        Path targetDir = SkillStore.safeSkillDir(poolDir, finalName);
        if (Files.exists(targetDir) && !overwrite) {
            Map<String, Object> r = SkillService.result(false, "conflict");
            r.put("suggested_name", SkillStore.suggestConflictName(finalName, SkillService.existingSkillNames(poolDir)));
            return r;
        }
        return savePoolSkillAsRename(normalizedName, finalName, content, config, oldEntry);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savePoolSkillInPlace(String skillName, String content,
                                                     Map<String, Object> config,
                                                     Map<String, Object> oldEntry) {
        Map<String, Object> newConfig = config != null ? config : SkillService.asMap(oldEntry.get("config"));
        Path poolDir = SkillStore.getSkillPoolDir();
        Path skillDir = SkillStore.safeSkillDir(poolDir, skillName);

        String oldMd = "";
        try {
            if (Files.isRegularFile(skillDir.resolve("SKILL.md"))) {
                oldMd = SkillStore.readTextFile(skillDir.resolve("SKILL.md"));
            }
        } catch (IOException ignored) {}
        boolean contentChanged = !content.equals(oldMd);
        boolean configSame = newConfig.equals(SkillService.asMap(oldEntry.get("config")));
        if (!contentChanged && configSame) {
            Map<String, Object> r = SkillService.result(true, null);
            r.put("mode", "noop");
            r.put("name", skillName);
            return r;
        }
        if (contentChanged) {
            Path stagedDir = null;
            try {
                stagedDir = SkillService.stagedSkillDir(skillName);
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
        String source = contentChanged ? "customized" : SkillService.str(oldEntry.get("source"), "customized");
        Map<String, Object> metadata = SkillStore.buildSkillMetadata(skillName, skillDir, source, false);

        SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(),
                payload -> {
                    Map<String, Object> skills = SkillService.asMap(payload.get("skills"));
                    Map<String, Object> currentEntry = SkillService.asMap(skills.get(skillName));
                    Map<String, Object> nextEntry = new LinkedHashMap<>(currentEntry);
                    nextEntry.put("version_text", metadata.get("version_text"));
                    nextEntry.put("commit_text", "");
                    nextEntry.put("source", metadata.get("source"));
                    nextEntry.put("protected", metadata.get("protected"));
                    nextEntry.put("requirements", metadata.get("requirements"));
                    nextEntry.put("updated_at", metadata.get("updated_at"));
                    nextEntry.put("description", metadata.get("description"));
                    nextEntry.put("metadata", metadata);
                    nextEntry.put("config", newConfig);
                    skills.put(skillName, nextEntry);
                    return null;
                });

        Map<String, Object> r = SkillService.result(true, null);
        r.put("mode", "edit");
        r.put("name", skillName);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savePoolSkillAsRename(String skillName, String finalName,
                                                      String content, Map<String, Object> config,
                                                      Map<String, Object> oldEntry) {
        Path poolDir = SkillStore.getSkillPoolDir();
        Path targetDir = SkillStore.safeSkillDir(poolDir, finalName);
        Path oldDir = SkillStore.safeSkillDir(poolDir, skillName);

        Path stagedDir = null;
        try {
            stagedDir = SkillService.stagedSkillDir(finalName);
            if (Files.exists(oldDir)) SkillStore.copySkillDir(oldDir, stagedDir);
            Files.write(stagedDir.resolve("SKILL.md"), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SkillScanner.scanSkillDirOrRaise(stagedDir, finalName);
            SkillStore.copySkillDir(stagedDir, targetDir);
        } catch (IOException e) {
            throw new SkillsError("Failed during pool rename save: " + e.getMessage());
        } finally {
            if (stagedDir != null) SkillStore.deleteRecursively(stagedDir);
        }
        Map<String, Object> oldConfig = config != null ? config : SkillService.asMap(oldEntry.get("config"));
        Map<String, Object> metadata = SkillStore.buildSkillMetadata(finalName, targetDir, "customized", false);

        SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(),
                payload -> {
                    Map<String, Object> skills = SkillService.asMap(payload.get("skills"));
                    Map<String, Object> currentEntry = SkillService.asMap(skills.get(skillName));
                    Map<String, Object> nextEntry = new LinkedHashMap<>();
                    nextEntry.put("enabled", false);
                    nextEntry.put("source", metadata.get("source"));
                    nextEntry.put("protected", metadata.get("protected"));
                    nextEntry.put("requirements", metadata.get("requirements"));
                    nextEntry.put("updated_at", metadata.get("updated_at"));
                    nextEntry.put("description", metadata.get("description"));
                    nextEntry.put("version_text", metadata.get("version_text"));
                    nextEntry.put("commit_text", "");
                    nextEntry.put("metadata", metadata);
                    nextEntry.put("config", oldConfig);
                    nextEntry.put("external", false);
                    if (currentEntry.get("tags") != null) nextEntry.put("tags", currentEntry.get("tags"));
                    if (currentEntry.get("installed_from") != null) nextEntry.put("installed_from", currentEntry.get("installed_from"));
                    skills.put(finalName, nextEntry);
                    skills.remove(skillName);
                    return null;
                });

        if (Files.exists(oldDir)) SkillStore.deleteRecursively(oldDir);
        // mirror rename into workspaces that carry the old name
        renameInWorkspaces(skillName, finalName, targetDir);

        Map<String, Object> r = SkillService.result(true, null);
        r.put("mode", "rename");
        r.put("name", finalName);
        return r;
    }

    @SuppressWarnings("unchecked")
    private void renameInWorkspaces(String oldName, String newName, Path sourceDir) {
        for (Map<String, String> ws : SkillRegistry.listWorkspaces()) {
            String agentId = ws.get("agent_id");
            Path workspaceDir = Path.of(ws.get("workspace_dir"));
            Map<String, Object> wsManifest = SkillStore.readWorkspaceManifest(workspaceDir);
            Map<String, Object> skills = SkillService.asMap(wsManifest.get("skills"));
            Map<String, Object> oldEntry = SkillService.asMap(skills.get(oldName));
            if (oldEntry == null || oldEntry.isEmpty()) continue;
            Path wsSkillsDir = SkillStore.getWorkspaceSkillsDir(workspaceDir);
            Path targetDir = SkillStore.safeSkillDir(wsSkillsDir, newName);
            Path oldDir = SkillStore.safeSkillDir(wsSkillsDir, oldName);
            try {
                Files.createDirectories(targetDir.getParent());
                SkillStore.copySkillDir(sourceDir, targetDir);
            } catch (Exception e) {
                log.warn("rename: failed migrating '{}'->'{}' in workspace '{}'", oldName, newName, agentId, e);
                continue;
            }
            final Map<String, Object> oldEntryF = oldEntry;
            final Path targetDirF = targetDir;
            SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                    SkillService.defaultWorkspaceManifest(),
                    payload -> {
                        Map<String, Object> s = SkillService.asMap(payload.get("skills"));
                        Map<String, Object> metadata = SkillStore.buildSkillMetadata(newName, targetDirF,
                                SkillService.str(oldEntryF.get("source"), "customized"), false);
                        Map<String, Object> wsEntry = new LinkedHashMap<>();
                        wsEntry.put("enabled", oldEntryF.get("enabled") != null ? oldEntryF.get("enabled") : true);
                        wsEntry.put("channels", oldEntryF.get("channels") != null ? oldEntryF.get("channels") : List.of("all"));
                        wsEntry.put("source", metadata.get("source"));
                        wsEntry.put("installed_from", oldEntryF.get("installed_from") != null ? oldEntryF.get("installed_from") : "");
                        wsEntry.put("config", oldEntryF.get("config") != null ? oldEntryF.get("config") : new LinkedHashMap<>());
                        wsEntry.put("metadata", metadata);
                        wsEntry.put("requirements", metadata.get("requirements"));
                        wsEntry.put("updated_at", metadata.get("updated_at"));
                        if (oldEntryF.get("builtin_language") != null) wsEntry.put("builtin_language", oldEntryF.get("builtin_language"));
                        if (oldEntryF.get("tags") != null) wsEntry.put("tags", oldEntryF.get("tags"));
                        s.put(newName, wsEntry);
                        s.remove(oldName);
                        return null;
                    });
            if (Files.exists(oldDir)) SkillStore.deleteRecursively(oldDir);
        }
    }

    // ------------------------------------------------------------------
    // Upload from workspace / download to workspace
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> uploadFromWorkspace(Path workspaceDir, String skillName,
                                                   boolean overwrite, boolean previewOnly) {
        String finalName;
        Path sourceDir;
        try {
            finalName = SkillStore.normalizeSkillDirName(skillName);
            sourceDir = SkillStore.safeSkillDir(SkillStore.getWorkspaceSkillsDir(workspaceDir), skillName);
        } catch (SkillsError e) {
            return SkillService.result(false, "not_found");
        }
        if (!Files.exists(sourceDir)) return SkillService.result(false, "not_found");
        Path targetDir = SkillStore.safeSkillDir(SkillStore.getSkillPoolDir(), finalName);
        Map<String, Object> manifest = SkillStore.readPoolManifest();
        Map<String, Object> existing = SkillService.asMap(SkillService.asMap(manifest.get("skills")).get(finalName));
        if (existing != null && !existing.isEmpty() && !overwrite) {
            Map<String, Object> r = SkillService.result(false, "conflict");
            r.put("suggested_name", SkillStore.suggestConflictName(finalName, null));
            return r;
        }
        if (previewOnly) {
            Map<String, Object> r = SkillService.result(true, null);
            r.put("name", finalName);
            return r;
        }
        Path stagedDir = null;
        try {
            stagedDir = SkillService.stagedSkillDir(finalName);
            SkillStore.copySkillDir(sourceDir, stagedDir);
            SkillScanner.scanSkillDirOrRaise(stagedDir, finalName);
            SkillStore.copySkillDir(stagedDir, targetDir);
        } finally {
            if (stagedDir != null) SkillStore.deleteRecursively(stagedDir);
        }
        Map<String, Object> wsManifest = SkillStore.readWorkspaceManifest(workspaceDir);
        Map<String, Object> workspaceEntry = SkillService.asMap(SkillService.asMap(wsManifest.get("skills")).get(skillName));
        Map<String, Object> wsConfig = SkillService.asMap(workspaceEntry.get("config"));
        Object wsTags = workspaceEntry.get("tags");
        String wsInstalledFrom = SkillService.str(workspaceEntry.get("installed_from"));

        final Path targetDirF = targetDir;
        SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(),
                payload -> {
                    registerPoolSkillEntry(payload, finalName, targetDirF, "customized", false,
                            wsInstalledFrom, wsConfig.isEmpty() ? null : wsConfig, wsTags, null);
                    return null;
                });
        Map<String, Object> r = SkillService.result(true, null);
        r.put("name", finalName);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checkDownloadConflict(Map<String, Object> entry, Map<String, Object> existing,
                                                      String finalName, Map<String, String> workspaceIdentity,
                                                      Path workspaceDir) {
        if (existing == null || existing.isEmpty()) return null;
        String wsId = workspaceIdentity.get("workspace_id");
        String wsName = workspaceIdentity.get("workspace_name");
        if ("builtin".equals(SkillService.str(entry.get("source")))
                && "builtin".equals(SkillService.str(existing.get("source")))) {
            String poolVer = SkillService.str(entry.get("version_text"));
            Map<String, Object> wsMeta = SkillService.asMap(existing.get("metadata"));
            String wsVer = SkillService.str(wsMeta.get("version_text"));
            if (!poolVer.isEmpty() && !wsVer.isEmpty() && poolVer.equals(wsVer)) {
                String poolLang = SkillService.str(entry.get("builtin_language"));
                String wsLang = SkillService.str(existing.get("builtin_language"));
                if (!poolLang.isEmpty() && !wsLang.isEmpty() && !poolLang.equals(wsLang)) {
                    Map<String, Object> c = SkillService.result(false, "language_switch");
                    c.put("workspace_id", wsId);
                    c.put("workspace_name", wsName);
                    c.put("skill_name", finalName);
                    c.put("source_language", poolLang);
                    c.put("current_language", wsLang);
                    return c;
                }
                if (!poolLang.isEmpty() && wsLang.isEmpty()) {
                    String poolHash = SkillStore.computeSkillMdHash(SkillStore.safeSkillDir(SkillStore.getSkillPoolDir(), finalName));
                    String wsHash = SkillStore.computeSkillMdHash(SkillStore.safeSkillDir(SkillStore.getWorkspaceSkillsDir(workspaceDir), finalName));
                    if (!poolHash.isEmpty() && !wsHash.isEmpty() && !poolHash.equals(wsHash)) {
                        Map<String, Object> c = SkillService.result(false, "language_switch");
                        c.put("workspace_id", wsId);
                        c.put("workspace_name", wsName);
                        c.put("skill_name", finalName);
                        c.put("source_language", poolLang);
                        c.put("current_language", wsLang);
                        return c;
                    }
                }
                Map<String, Object> ok = SkillService.result(true, null);
                ok.put("mode", "unchanged");
                ok.put("name", finalName);
                ok.put("workspace_id", wsId);
                ok.put("workspace_name", wsName);
                ok.put("backfill_language", poolLang);
                return ok;
            }
            Map<String, Object> c = SkillService.result(false, "builtin_upgrade");
            c.put("workspace_id", wsId);
            c.put("workspace_name", wsName);
            c.put("skill_name", finalName);
            c.put("source_version_text", poolVer);
            c.put("current_version_text", wsVer);
            return c;
        }
        Map<String, Object> c = SkillService.result(false, "conflict");
        c.put("workspace_id", wsId);
        c.put("workspace_name", wsName);
        c.put("suggested_name", SkillStore.suggestConflictName(finalName, null));
        return c;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> preflightDownloadToWorkspace(String skillName, Path workspaceDir, boolean overwrite) {
        String finalName;
        try {
            finalName = SkillStore.normalizeSkillDirName(skillName);
        } catch (SkillsError e) {
            return SkillService.result(false, "not_found");
        }
        Map<String, Object> manifest = SkillStore.readPoolManifest();
        Map<String, Object> entry = SkillService.asMap(SkillService.asMap(manifest.get("skills")).get(skillName));
        if (entry == null || entry.isEmpty()) return SkillService.result(false, "not_found");
        Map<String, Object> wsManifest = SkillStore.readWorkspaceManifest(workspaceDir);
        Map<String, Object> existing = SkillService.asMap(SkillService.asMap(wsManifest.get("skills")).get(finalName));
        if (!overwrite) {
            Map<String, String> identity = SkillStore.getWorkspaceIdentity(workspaceDir);
            Map<String, Object> conflict = checkDownloadConflict(entry, existing, finalName, identity, workspaceDir);
            if (conflict != null) return conflict;
        }
        return SkillService.result(true, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> downloadToWorkspace(String skillName, Path workspaceDir, boolean overwrite) {
        String finalName;
        try {
            finalName = SkillStore.normalizeSkillDirName(skillName);
        } catch (SkillsError e) {
            return SkillService.result(false, "not_found");
        }
        Map<String, Object> manifest = SkillStore.readPoolManifest();
        Map<String, Object> entry = SkillService.asMap(SkillService.asMap(manifest.get("skills")).get(skillName));
        if (entry == null || entry.isEmpty()) return SkillService.result(false, "not_found");
        Path sourceDir = SkillStore.resolvePoolSkillDir(skillName);
        if (sourceDir == null) return SkillService.result(false, "not_found");
        Path targetDir = SkillStore.safeSkillDir(SkillStore.getWorkspaceSkillsDir(workspaceDir), finalName);
        Map<String, Object> wsManifest = SkillStore.readWorkspaceManifest(workspaceDir);
        Map<String, Object> existing = SkillService.asMap(SkillService.asMap(wsManifest.get("skills")).get(finalName));
        Map<String, String> identity = SkillStore.getWorkspaceIdentity(workspaceDir);
        if (!overwrite) {
            Map<String, Object> conflict = checkDownloadConflict(entry, existing, finalName, identity, workspaceDir);
            if (conflict != null) {
                Object backfill = conflict.get("backfill_language");
                if (backfill != null && !String.valueOf(backfill).isEmpty()) {
                    backfillWorkspaceLanguage(workspaceDir, finalName, String.valueOf(backfill));
                }
                return conflict;
            }
        }
        try {
            Files.createDirectories(targetDir.getParent());
        } catch (IOException e) {
            throw new SkillsError("Cannot create workspace skills dir");
        }
        Path stagedDir = null;
        try {
            stagedDir = SkillService.stagedSkillDir(finalName);
            SkillStore.copySkillDir(sourceDir, stagedDir);
            SkillScanner.scanSkillDirOrRaise(stagedDir, finalName);
            SkillStore.copySkillDir(stagedDir, targetDir);
        } finally {
            if (stagedDir != null) SkillStore.deleteRecursively(stagedDir);
        }
        final Path targetDirF = targetDir;
        final Map<String, Object> entryF = entry;
        SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                SkillService.defaultWorkspaceManifest(),
                payload -> {
                    Map<String, Object> skills = SkillService.asMap(payload.get("skills"));
                    String source = SkillService.str(entryF.get("source"), "customized");
                    Map<String, Object> metadata = SkillStore.buildSkillMetadata(finalName, targetDirF, source, false);
                    Map<String, Object> wsEntry = new LinkedHashMap<>();
                    wsEntry.put("enabled", true);
                    wsEntry.put("channels", List.of("all"));
                    wsEntry.put("source", metadata.get("source"));
                    wsEntry.put("installed_from", "pool");
                    wsEntry.put("config", new LinkedHashMap<>());
                    wsEntry.put("metadata", metadata);
                    wsEntry.put("requirements", metadata.get("requirements"));
                    wsEntry.put("updated_at", metadata.get("updated_at"));
                    if (entryF.get("builtin_language") != null) wsEntry.put("builtin_language", entryF.get("builtin_language"));
                    skills.put(finalName, wsEntry);
                    return null;
                });
        Map<String, Object> r = SkillService.result(true, null);
        r.put("workspace_name", identity.get("workspace_name"));
        r.put("name", finalName);
        return r;
    }

    @SuppressWarnings("unchecked")
    private void backfillWorkspaceLanguage(Path workspaceDir, String skillName, String language) {
        SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                SkillService.defaultWorkspaceManifest(),
                payload -> {
                    Map<String, Object> entry = SkillService.asMap(SkillService.asMap(payload.get("skills")).get(skillName));
                    if (entry != null && !entry.isEmpty()) entry.put("builtin_language", language);
                    return null;
                });
    }

    // ------------------------------------------------------------------
    // Auto-update sync
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> runPoolAutoUpdateSync(String skillName) {
        Map<String, Object> manifest = SkillStore.readPoolManifest();
        Map<String, Object> entries = SkillService.asMap(manifest.get("skills"));
        List<String> synced = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            String name = e.getKey();
            if (skillName != null && !skillName.isEmpty() && !name.equals(skillName)) continue;
            Map<String, Object> entry = SkillService.asMap(e.getValue());
            if (!SkillService.bool(entry.get("auto_update"), false)) continue;
            checked++;
            List<String> targets = SkillService.toStringList(entry.get("auto_update_targets"));
            if (targets.isEmpty()) continue;
            boolean ok = true;
            for (String agentId : targets) {
                Path wsDir = SkillRegistry.workspaceDirForAgent(agentId);
                Map<String, Object> r = downloadToWorkspace(name, wsDir, true);
                if (!SkillService.bool(r.get("success"), false)) { ok = false; failed.add(agentId); }
            }
            if (ok) {
                synced.add(name);
                SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(), SkillService.defaultPoolManifest(),
                        payload -> {
                            Map<String, Object> en = SkillService.asMap(SkillService.asMap(payload.get("skills")).get(name));
                            if (en != null && !en.isEmpty()) en.put("auto_update_synced_hash", SkillStore.computeSkillMdHash(SkillStore.safeSkillDir(SkillStore.getSkillPoolDir(), name)));
                            return null;
                        });
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("synced", synced);
        r.put("failed", failed);
        r.put("checked", checked);
        return r;
    }

    // ------------------------------------------------------------------
    // Entry registration (single source of truth for pool entry shape)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static void registerPoolSkillEntry(Map<String, Object> payload, String skillName, Path skillDir,
                                              String source, boolean protectedFlag, String installedFrom,
                                              Map<String, Object> config, Object tags,
                                              Map<String, Object> preserveFrom) {
        Map<String, Object> skills = SkillService.asMap(payload.get("skills"));
        Map<String, Object> preserve = preserveFrom != null ? preserveFrom : SkillService.asMap(skills.get(skillName));
        Map<String, Object> entry = SkillStore.buildSkillMetadata(skillName, skillDir, source, protectedFlag);
        entry.put("external", !SkillStore.isPrimaryPoolSkillDir(skillDir));
        String installedFromFinal = installedFrom != null && !installedFrom.isEmpty()
                ? installedFrom : SkillService.str(preserve.get("installed_from"));
        if (!installedFromFinal.isEmpty()) entry.put("installed_from", installedFromFinal);
        if (config != null) entry.put("config", new LinkedHashMap<>(config));
        else if (preserve.containsKey("config")) entry.put("config", preserve.get("config"));
        if (tags != null) entry.put("tags", tags);
        else if (preserve.get("tags") != null) entry.put("tags", preserve.get("tags"));
        if ("builtin".equals(source)) {
            String builtinLanguage = SkillService.str(preserve.get("builtin_language")).trim().toLowerCase();
            if (!builtinLanguage.isEmpty()) entry.put("builtin_language", builtinLanguage);
            String builtinSourceName = SkillService.str(preserve.get("builtin_source_name")).trim();
            if (!builtinSourceName.isEmpty()) entry.put("builtin_source_name", builtinSourceName);
        }
        for (String key : List.of("auto_update", "auto_update_targets", "auto_update_synced_hash")) {
            if (preserve.containsKey(key)) entry.put(key, preserve.get(key));
        }
        skills.put(skillName, entry);
    }
}
