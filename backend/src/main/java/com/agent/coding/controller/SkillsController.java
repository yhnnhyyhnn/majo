package com.agent.coding.controller;

import com.agent.coding.SettingsService;
import com.agent.coding.skill.*;
import com.agent.coding.skill.SkillHubService.SkillConflictErrorDetail;
import com.agent.coding.skill.SkillHubService.HubInstallResult;
import com.agent.coding.skill.SkillHubService.SkillImportCancelled;
import com.agent.coding.skill.SkillModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skills API (Java port of qwenpaw/app/routers/skills.py + skills_stream.py).
 *
 * <p>majo runs a single implicit agent ({@code agent_id="default"}) rooted at
 * {@code user.dir}; workspace resolution follows the frontend contract:
 * {@code X-Agent-Id} header, else {@code ?agent=}, else default.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SkillsController {

    private static final Logger log = LoggerFactory.getLogger(SkillsController.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final SkillTaskManager taskManager = new SkillTaskManager();
    private final SkillHubService hubService = new SkillHubService();
    private final ExecutorService hubExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "skill-hub-install");
        t.setDaemon(true);
        return t;
    });

    private final SettingsService settingsService;

    public SkillsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // ------------------------------------------------------------------
    // Workspace resolution
    // ------------------------------------------------------------------

    private Path resolveWorkspace(HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getParameter("agent");
        }
        return SkillRegistry.workspaceDirForAgent(agentId);
    }

    private Path resolveWorkspace(Map<String, Object> body) {
        Object wsId = body.get("workspace_id");
        return SkillRegistry.workspaceDirForAgent(wsId == null ? null : String.valueOf(wsId));
    }

    // ------------------------------------------------------------------
    // List / refresh / workspaces
    // ------------------------------------------------------------------

    @GetMapping("/skills")
    public List<SkillSpec> listSkills(HttpServletRequest request) {
        return new SkillService(resolveWorkspace(request)).buildSkillSpecs();
    }

    @PostMapping("/skills/refresh")
    public List<SkillSpec> refreshSkills(HttpServletRequest request) {
        Path workspaceDir = resolveWorkspace(request);
        SkillRegistry.reconcileWorkspaceManifest(workspaceDir);
        return new SkillService(workspaceDir).buildSkillSpecs();
    }

    @GetMapping("/skills/workspaces")
    public List<WorkspaceSkillSummary> listWorkspaces() {
        List<WorkspaceSkillSummary> summaries = new ArrayList<>();
        for (Map<String, String> ws : SkillRegistry.listWorkspaces()) {
            Path workspaceDir = Path.of(ws.get("workspace_dir"));
            WorkspaceSkillSummary s = new WorkspaceSkillSummary();
            s.agentId = ws.get("agent_id");
            s.agentName = ws.getOrDefault("agent_name", "");
            s.workspaceDir = workspaceDir.toString();
            s.skills = new SkillService(workspaceDir).buildSkillSpecs();
            summaries.add(s);
        }
        return summaries;
    }

    // ------------------------------------------------------------------
    // Hub search + install tasks
    // ------------------------------------------------------------------

    @GetMapping("/skills/hub/search")
    public List<HubSkillSpec> searchHub(@RequestParam(defaultValue = "") String q,
                                        @RequestParam(defaultValue = "20") int limit) {
        return hubService.searchHubSkills(q, limit);
    }

    @PostMapping("/skills/hub/install/start")
    public HubInstallTask startHubInstall(@RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        Path workspaceDir = resolveWorkspace(request);
        String bundleUrl = SkillService.str(body.get("bundle_url"));
        String version = SkillService.str(body.get("version"));
        boolean enable = SkillService.bool(body.get("enable"), false);
        String targetName = SkillService.str(body.get("target_name"));

        HubInstallTask task = new HubInstallTask(UUID.randomUUID().toString(),
                bundleUrl, version, enable, "pending");
        AtomicBoolean cancelEvent = new AtomicBoolean(false);
        taskManager.put(task);
        taskManager.attachCancelEvent(task.taskId, cancelEvent);

        Thread worker = new Thread(() -> runHubInstallTask(task, workspaceDir,
                bundleUrl, version, enable, targetName, cancelEvent));
        worker.setDaemon(true);
        taskManager.attachRuntimeThread(task.taskId, worker);
        worker.start();
        return task;
    }

    @GetMapping("/skills/hub/install/status/{task_id}")
    public HubInstallTask getHubInstallStatus(@PathVariable("task_id") String taskId) {
        HubInstallTask task = taskManager.get(taskId);
        if (task == null) throw new SkillNotFoundException("install task not found");
        return task;
    }

    @PostMapping("/skills/hub/install/cancel/{task_id}")
    public Map<String, Object> cancelHubInstall(@PathVariable("task_id") String taskId) {
        String status = taskManager.cancel(taskId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("task_id", taskId);
        r.put("status", status);
        return r;
    }

    private void runHubInstallTask(HubInstallTask task, Path workspaceDir, String bundleUrl,
                                   String version, boolean enable, String targetName,
                                   AtomicBoolean cancelEvent) {
        taskManager.setStatus(task.taskId, "importing");
        String importedName = null;
        try {
            HubInstallResult result = hubService.installSkillFromHub(
                    workspaceDir, bundleUrl, version, enable, targetName, cancelEvent);
            importedName = result.name();
            if (cancelEvent.get()) {
                cleanupImportedSkill(workspaceDir, result.name());
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("installed", false);
                res.put("name", result.name());
                res.put("enabled", false);
                res.put("source_url", result.sourceUrl());
                res.put("installed_from", result.installedFrom());
                taskManager.finish(task.taskId, "cancelled", null, res);
                return;
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("installed", true);
            res.put("name", result.name());
            res.put("enabled", result.enabled());
            res.put("source_url", result.sourceUrl());
            res.put("installed_from", result.installedFrom());
            taskManager.finish(task.taskId, "completed", null, res);
        } catch (SkillImportCancelled e) {
            if (importedName != null) cleanupImportedSkill(workspaceDir, importedName);
            taskManager.finish(task.taskId, "cancelled", null, null);
        } catch (SkillScanError e) {
            taskManager.finish(task.taskId, "failed", e.getMessage(), scanErrorPayload(e));
        } catch (SkillConflictErrorDetail e) {
            taskManager.finish(task.taskId, "failed", e.getMessage(), e.detail);
        } catch (SkillConflictError e) {
            taskManager.finish(task.taskId, "failed", e.getMessage(), null);
        } catch (SkillsError e) {
            taskManager.finish(task.taskId, "failed", e.getMessage(), null);
        } catch (Exception e) {
            log.error("Skill hub import failed", e);
            taskManager.finish(task.taskId, "failed", "Skill hub import failed: " + e.getMessage(), null);
        }
    }

    private void cleanupImportedSkill(Path workspaceDir, String skillName) {
        try {
            SkillService svc = new SkillService(workspaceDir);
            svc.disableSkill(skillName);
            svc.deleteSkill(skillName);
        } catch (Exception e) {
            log.warn("Cleanup of imported skill '{}' failed: {}", skillName, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Pool list / builtins
    // ------------------------------------------------------------------

    @GetMapping("/skills/pool")
    public List<PoolSkillSpec> listPoolSkills() {
        return new SkillPoolService().buildPoolSkillSpecs();
    }

    @PostMapping("/skills/pool/refresh")
    public List<PoolSkillSpec> refreshPoolSkills() {
        SkillRegistry.reconcilePoolManifest();
        followAutoUpdate(null);
        return new SkillPoolService().buildPoolSkillSpecs();
    }

    @GetMapping("/skills/pool/builtin-sources")
    public List<Map<String, Object>> listPoolBuiltinSources() {
        return SkillRegistry.listBuiltinImportCandidates();
    }

    @GetMapping("/skills/pool/builtin-notice")
    public Map<String, Object> getPoolBuiltinNotice() {
        return SkillRegistry.getPoolBuiltinUpdateNotice();
    }

    @PostMapping("/skills/pool/import-builtin")
    public Map<String, Object> importPoolBuiltins(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> imports = new ArrayList<>();
        Object rawImports = body.get("imports");
        if (rawImports instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> req = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) req.put(String.valueOf(e.getKey()), e.getValue());
                    imports.add(req);
                }
            }
        } else {
            Object skillNames = body.get("skill_names");
            if (skillNames instanceof List<?> names) {
                for (Object n : names) {
                    Map<String, Object> req = new LinkedHashMap<>();
                    req.put("skill_name", String.valueOf(n));
                    imports.add(req);
                }
            }
        }
        boolean overwriteConflicts = SkillService.bool(body.get("overwrite_conflicts"), false);
        Map<String, Object> result = SkillRegistry.importBuiltinSkills(imports, overwriteConflicts);
        if (result.get("conflicts") != null && !((List<?>) result.get("conflicts")).isEmpty()
                && !overwriteConflicts) {
            throw new SkillConflictError(payloadJson(result));
        }
        followAutoUpdate(null);
        return result;
    }

    @PostMapping("/skills/pool/{skill_name}/update-builtin")
    public Map<String, Object> updatePoolBuiltin(@PathVariable("skill_name") String skillName,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        String language = body == null ? "" : SkillService.str(body.get("language"));
        if (!language.isEmpty() && !SkillRegistry.BUILTIN_SKILL_LANGUAGES.contains(language)) {
            throw new SkillsError("Invalid language '" + language + "', "
                    + "must be one of " + SkillRegistry.BUILTIN_SKILL_LANGUAGES);
        }
        Map<String, Object> result = SkillRegistry.updateSingleBuiltin(skillName, language.isEmpty() ? null : language);
        followAutoUpdate(null);
        return result;
    }

    // ------------------------------------------------------------------
    // Create / save / upload (workspace + pool)
    // ------------------------------------------------------------------

    @PostMapping("/skills")
    public Map<String, Object> createSkill(@RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        Path workspaceDir = resolveWorkspace(request);
        try {
            String created = new SkillService(workspaceDir).createSkill(
                    SkillService.str(body.get("name")),
                    SkillService.str(body.get("content")),
                    asMapOrNull(body.get("references")),
                    asMapOrNull(body.get("scripts")),
                    asMapOrNull(body.get("extra_files")),
                    asMapOrNull(body.get("config")),
                    SkillService.bool(body.get("enable"), true),
                    "", "customized");
            if (created == null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("reason", "conflict");
                detail.put("suggested_name", SkillStore.suggestConflictName(
                        SkillService.str(body.get("name")), null));
                throw new SkillConflictError(payloadJson(detail));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("created", true);
            r.put("name", created);
            return r;
        } catch (SkillScanError | SkillConflictError e) {
            throw e;
        }
    }

    @PostMapping("/skills/upload")
    public Map<String, Object> uploadSkillZip(@RequestParam("file") MultipartFile file,
                                              @RequestParam(defaultValue = "true") boolean enable,
                                              @RequestParam(defaultValue = "") String target_name,
                                              @RequestParam(defaultValue = "") String rename_map,
                                              HttpServletRequest request) {
        Path workspaceDir = resolveWorkspace(request);
        Map<String, String> parsedRename = parseRenameMap(rename_map);
        try {
            Map<String, Object> result = new SkillService(workspaceDir).importFromZip(
                    readBytes(file), enable, target_name, parsedRename);
            if (result.get("conflicts") != null && !((List<?>) result.get("conflicts")).isEmpty()) {
                throw new SkillConflictError(payloadJson(result));
            }
            return result;
        } catch (SkillScanError | SkillConflictError e) {
            throw e;
        }
    }

    @PostMapping("/skills/pool/create")
    public Map<String, Object> createPoolSkill(@RequestBody Map<String, Object> body) {
        try {
            String created = new SkillPoolService().createSkill(
                    SkillService.str(body.get("name")),
                    SkillService.str(body.get("content")),
                    asMapOrNull(body.get("references")),
                    asMapOrNull(body.get("scripts")),
                    asMapOrNull(body.get("config")),
                    "");
            if (created == null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("reason", "conflict");
                detail.put("suggested_name", SkillStore.suggestConflictName(
                        SkillService.str(body.get("name")), null));
                throw new SkillConflictError(payloadJson(detail));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("created", true);
            r.put("name", created);
            return r;
        } catch (SkillScanError | SkillConflictError e) {
            throw e;
        }
    }

    @PutMapping("/skills/pool/save")
    public Map<String, Object> savePoolSkill(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> result = new SkillPoolService().savePoolSkill(
                    SkillService.str(body.getOrDefault("source_name", body.get("name"))),
                    SkillService.str(body.get("content")),
                    SkillService.str(body.get("name")),
                    asMapOrNull(body.get("config")),
                    SkillService.bool(body.get("overwrite"), false));
            if (!SkillService.bool(result.get("success"), false)) {
                String reason = SkillService.str(result.get("reason"));
                if ("not_found".equals(reason)) throw new SkillNotFoundException(payloadJson(result));
                throw new SkillConflictError(payloadJson(result));
            }
            followAutoUpdate(SkillService.str(result.get("name")));
            return result;
        } catch (SkillScanError | SkillConflictError | SkillNotFoundException e) {
            throw e;
        }
    }

    @PostMapping("/skills/pool/upload-zip")
    public Map<String, Object> uploadSkillPoolZip(@RequestParam("file") MultipartFile file,
                                                  @RequestParam(defaultValue = "") String target_name,
                                                  @RequestParam(defaultValue = "") String rename_map) {
        Map<String, String> parsedRename = parseRenameMap(rename_map);
        try {
            Map<String, Object> result = new SkillPoolService().importFromZip(
                    readBytes(file), target_name, parsedRename);
            if (result.get("conflicts") != null && !((List<?>) result.get("conflicts")).isEmpty()) {
                throw new SkillConflictError(payloadJson(result));
            }
            followAutoUpdate(null);
            return result;
        } catch (SkillScanError | SkillConflictError e) {
            throw e;
        }
    }

    @PostMapping("/skills/pool/import")
    public Map<String, Object> importPoolSkillFromHub(@RequestBody Map<String, Object> body) {
        try {
            HubInstallResult result = hubService.importPoolSkillFromHub(
                    SkillService.str(body.get("bundle_url")),
                    SkillService.str(body.get("version")),
                    SkillService.str(body.get("target_name")),
                    new AtomicBoolean(false));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("installed", true);
            r.put("name", result.name());
            r.put("enabled", false);
            r.put("source_url", result.sourceUrl());
            r.put("installed_from", result.installedFrom());
            followAutoUpdate(result.name());
            return r;
        } catch (SkillScanError | SkillConflictError e) {
            throw e;
        }
    }

    @PostMapping("/skills/pool/upload")
    public Map<String, Object> uploadWorkspaceSkillToPool(@RequestBody Map<String, Object> body) {
        Path workspaceDir = resolveWorkspace(body);
        try {
            Map<String, Object> result = new SkillPoolService().uploadFromWorkspace(
                    workspaceDir,
                    SkillService.str(body.get("skill_name")),
                    SkillService.bool(body.get("overwrite"), false),
                    SkillService.bool(body.get("preview_only"), false));
            if (!SkillService.bool(result.get("success"), false)) {
                String reason = SkillService.str(result.get("reason"));
                if ("not_found".equals(reason)) throw new SkillNotFoundException(payloadJson(result));
                throw new SkillConflictError(payloadJson(result));
            }
            followAutoUpdate(SkillService.str(result.get("name")));
            return result;
        } catch (SkillScanError | SkillConflictError | SkillNotFoundException e) {
            throw e;
        }
    }

    @PostMapping("/skills/pool/download")
    public Map<String, Object> downloadPoolSkillToWorkspaces(@RequestBody Map<String, Object> body) {
        String skillName = SkillService.str(body.get("skill_name"));
        boolean overwrite = SkillService.bool(body.get("overwrite"), false);
        boolean previewOnly = SkillService.bool(body.get("preview_only"), false);
        boolean allWorkspaces = SkillService.bool(body.get("all_workspaces"), false);

        List<Map<String, Object>> targets = new ArrayList<>();
        Object rawTargets = body.get("targets");
        if (allWorkspaces) {
            for (Map<String, String> ws : SkillRegistry.listWorkspaces()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("workspace_id", ws.get("agent_id"));
                targets.add(t);
            }
        } else if (rawTargets instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) t.put(String.valueOf(e.getKey()), e.getValue());
                    targets.add(t);
                }
            }
        }
        if (targets.isEmpty()) {
            throw new SkillsError("No workspace targets provided");
        }

        SkillPoolService pool = new SkillPoolService();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map<String, Object> target : targets) {
            Path wsDir = resolveWorkspace(target);
            Map<String, Object> preflight = pool.preflightDownloadToWorkspace(skillName, wsDir, overwrite);
            if (!SkillService.bool(preflight.get("success"), false)) {
                if ("not_found".equals(SkillService.str(preflight.get("reason")))) {
                    throw new SkillNotFoundException(payloadJson(preflight));
                }
                conflicts.add(preflight);
            }
        }
        if (!conflicts.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("downloaded", new ArrayList<>());
            detail.put("conflicts", conflicts);
            throw new SkillConflictError(payloadJson(detail));
        }
        if (previewOnly) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("downloaded", new ArrayList<>());
            return r;
        }

        List<Map<String, Object>> downloaded = new ArrayList<>();
        for (Map<String, Object> target : targets) {
            Path wsDir = resolveWorkspace(target);
            Map<String, Object> result = pool.downloadToWorkspace(skillName, wsDir, overwrite);
            if (!SkillService.bool(result.get("success"), false)) {
                String reason = SkillService.str(result.get("reason"));
                if ("not_found".equals(reason)) throw new SkillNotFoundException(payloadJson(result));
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("downloaded", new ArrayList<>());
                detail.put("conflicts", List.of(result));
                throw new SkillConflictError(payloadJson(detail));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("workspace_id", target.get("workspace_id"));
            item.put("workspace_name", result.get("workspace_name"));
            item.put("name", result.get("name"));
            downloaded.add(item);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("downloaded", downloaded);
        return r;
    }

    // ------------------------------------------------------------------
    // Pool skill config / tags / auto-update / delete
    // ------------------------------------------------------------------

    @DeleteMapping("/skills/pool/{skill_name}")
    public Map<String, Object> deletePoolSkill(@PathVariable("skill_name") String skillName) {
        boolean deleted = new SkillPoolService().deleteSkill(skillName);
        if (!deleted) throw new SkillConflictError("Skill pool entry cannot be deleted");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("deleted", true);
        return r;
    }

    @GetMapping("/skills/pool/{skill_name}/config")
    public Map<String, Object> getPoolSkillConfig(@PathVariable("skill_name") String skillName) {
        Map<String, Object> manifest = SkillStore.readPoolManifest();
        Map<String, Object> entry = SkillService.asMap(SkillService.asMap(manifest.get("skills")).get(skillName));
        if (entry == null || entry.isEmpty()) throw new SkillNotFoundException("Pool skill not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("config", entry.get("config") != null ? entry.get("config") : new LinkedHashMap<>());
        return r;
    }

    @PutMapping("/skills/pool/{skill_name}/config")
    public Map<String, Object> updatePoolSkillConfig(@PathVariable("skill_name") String skillName,
                                                     @RequestBody Map<String, Object> body) {
        Map<String, Object> config = asMapOrNull(body.get("config"));
        Boolean updated = SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(),
                SkillService.defaultPoolManifest(), payload -> {
                    Map<String, Object> entry = SkillService.asMap(SkillService.asMap(payload.get("skills")).get(skillName));
                    if (entry == null || entry.isEmpty()) return false;
                    entry.put("config", config != null ? config : new LinkedHashMap<>());
                    return true;
                });
        if (!Boolean.TRUE.equals(updated)) throw new SkillNotFoundException("Pool skill not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("updated", true);
        return r;
    }

    @DeleteMapping("/skills/pool/{skill_name}/config")
    public Map<String, Object> deletePoolSkillConfig(@PathVariable("skill_name") String skillName) {
        Boolean updated = SkillStore.mutateJson(SkillStore.getPoolSkillManifestPath(),
                SkillService.defaultPoolManifest(), payload -> {
                    Map<String, Object> entry = SkillService.asMap(SkillService.asMap(payload.get("skills")).get(skillName));
                    if (entry == null || entry.isEmpty()) return false;
                    entry.remove("config");
                    return true;
                });
        if (!Boolean.TRUE.equals(updated)) throw new SkillNotFoundException("Pool skill not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cleared", true);
        return r;
    }

    @PutMapping("/skills/pool/{skill_name}/tags")
    public Map<String, Object> updatePoolSkillTags(@PathVariable("skill_name") String skillName,
                                                   @RequestBody List<String> tags) {
        List<String> cleaned = validateTags(tags);
        boolean updated = new SkillPoolService().setPoolSkillTags(skillName, cleaned);
        if (!updated) throw new SkillNotFoundException("Pool skill not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("updated", true);
        r.put("tags", cleaned);
        return r;
    }

    @PutMapping("/skills/pool/{skill_name}/auto-update")
    public Map<String, Object> updatePoolSkillAutoUpdate(@PathVariable("skill_name") String skillName,
                                                         @RequestBody Map<String, Object> body) {
        boolean enabled = SkillService.bool(body.get("enabled"), false);
        List<String> targets = SkillService.toStringListOrAll(body.get("targets"));
        Map<String, Object> result = new SkillPoolService().setSkillAutoUpdate(skillName, enabled, targets);
        if (result == null) throw new SkillNotFoundException("Pool skill not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("updated", true);
        r.put("enabled", enabled);
        r.put("targets", targets.isEmpty() ? null : targets);
        return r;
    }

    // ------------------------------------------------------------------
    // Batch operations
    // ------------------------------------------------------------------

    @PostMapping("/skills/batch-delete")
    public Map<String, Object> batchDeleteSkills(@RequestBody List<String> skills,
                                                 HttpServletRequest request) {
        Path workspaceDir = resolveWorkspace(request);
        SkillService svc = new SkillService(workspaceDir);
        Map<String, Object> results = new LinkedHashMap<>();
        for (String skillName : skills) {
            try {
                svc.disableSkill(skillName);
                boolean deleted = svc.deleteSkill(skillName);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("success", deleted);
                item.put("reason", deleted ? null : "delete_failed");
                results.put(skillName, item);
            } catch (Exception e) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("success", false);
                item.put("reason", e.getMessage());
                results.put(skillName, item);
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("results", results);
        return r;
    }

    @PostMapping("/skills/pool/batch-delete")
    public Map<String, Object> batchDeletePoolSkills(@RequestBody List<String> skills) {
        SkillPoolService pool = new SkillPoolService();
        Map<String, Object> results = new LinkedHashMap<>();
        for (String skillName : skills) {
            try {
                boolean deleted = pool.deleteSkill(skillName);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("success", deleted);
                item.put("reason", deleted ? null : "delete_failed");
                results.put(skillName, item);
            } catch (Exception e) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("success", false);
                item.put("reason", e.getMessage());
                results.put(skillName, item);
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("results", results);
        return r;
    }

    @PostMapping("/skills/batch-disable")
    public Map<String, Object> batchDisableSkills(@RequestBody List<String> skills,
                                                  HttpServletRequest request) {
        Path workspaceDir = resolveWorkspace(request);
        SkillService svc = new SkillService(workspaceDir);
        Map<String, Object> results = new LinkedHashMap<>();
        for (String skillName : skills) {
            results.put(skillName, svc.disableSkill(skillName));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("results", results);
        return r;
    }

    @PostMapping("/skills/batch-enable")
    public Map<String, Object> batchEnableSkills(@RequestBody List<String> skills,
                                                 HttpServletRequest request) {
        Path workspaceDir = resolveWorkspace(request);
        SkillService svc = new SkillService(workspaceDir);
        Map<String, Object> results = new LinkedHashMap<>();
        for (String skillName : skills) {
            try {
                results.put(skillName, svc.enableSkill(skillName, null));
            } catch (SkillScanError e) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("success", false);
                item.put("reason", "security_scan_failed");
                item.put("detail", scanErrorPayload(e));
                results.put(skillName, item);
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("results", results);
        return r;
    }

    // ------------------------------------------------------------------
    // Workspace skill operations
    // ------------------------------------------------------------------

    @PostMapping("/skills/{skill_name}/disable")
    public Map<String, Object> disableSkill(@PathVariable("skill_name") String skillName,
                                            HttpServletRequest request) {
        Map<String, Object> result = new SkillService(resolveWorkspace(request)).disableSkill(skillName);
        if (!SkillService.bool(result.get("success"), false)) {
            throw new SkillNotFoundException("Skill not found");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("disabled", true);
        r.putAll(result);
        return r;
    }

    @PostMapping("/skills/{skill_name}/enable")
    public Map<String, Object> enableSkill(@PathVariable("skill_name") String skillName,
                                           HttpServletRequest request) {
        try {
            Map<String, Object> result = new SkillService(resolveWorkspace(request))
                    .enableSkill(skillName, null);
            if (!SkillService.bool(result.get("success"), false)) {
                throw new SkillNotFoundException(
                        SkillService.str(result.get("reason"), "Skill not found"));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("enabled", true);
            r.putAll(result);
            return r;
        } catch (SkillScanError e) {
            throw e;
        }
    }

    @DeleteMapping("/skills/{skill_name}")
    public Map<String, Object> deleteSkill(@PathVariable("skill_name") String skillName,
                                           HttpServletRequest request) {
        SkillService svc = new SkillService(resolveWorkspace(request));
        svc.disableSkill(skillName);
        boolean deleted = svc.deleteSkill(skillName);
        if (!deleted) throw new SkillConflictError("Only disabled workspace skills can be deleted");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("deleted", true);
        return r;
    }

    @GetMapping("/skills/{skill_name}/files/{file_path:.*}")
    public Map<String, Object> loadSkillFile(@PathVariable("skill_name") String skillName,
                                             @PathVariable("file_path") String filePath,
                                             HttpServletRequest request) {
        String content = new SkillService(resolveWorkspace(request)).loadSkillFile(skillName, filePath);
        if (content == null) throw new SkillNotFoundException("File not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", content);
        return r;
    }

    @PutMapping("/skills/save")
    public Map<String, Object> saveWorkspaceSkill(@RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        Path workspaceDir = resolveWorkspace(request);
        try {
            Map<String, Object> result = new SkillService(workspaceDir).saveSkill(
                    SkillService.str(body.getOrDefault("source_name", body.get("name"))),
                    SkillService.str(body.get("content")),
                    body.containsKey("source_name") ? SkillService.str(body.get("name")) : null,
                    asMapOrNull(body.get("config")),
                    SkillService.bool(body.get("overwrite"), false));
            if (!SkillService.bool(result.get("success"), false)) {
                if ("conflict".equals(SkillService.str(result.get("reason")))) {
                    throw new SkillConflictError(payloadJson(result));
                }
                throw new SkillNotFoundException("Skill not found");
            }
            return result;
        } catch (SkillScanError | SkillConflictError | SkillNotFoundException e) {
            throw e;
        }
    }

    @PutMapping("/skills/{skill_name}/channels")
    public Map<String, Object> updateSkillChannels(@PathVariable("skill_name") String skillName,
                                                   @RequestBody List<String> channels,
                                                   HttpServletRequest request) {
        boolean updated = new SkillService(resolveWorkspace(request)).setSkillChannels(skillName, channels);
        if (!updated) throw new SkillNotFoundException("Skill not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("updated", true);
        r.put("channels", channels);
        return r;
    }

    @PutMapping("/skills/{skill_name}/tags")
    public Map<String, Object> updateSkillTags(@PathVariable("skill_name") String skillName,
                                               @RequestBody List<String> tags,
                                               HttpServletRequest request) {
        List<String> cleaned = validateTags(tags);
        boolean updated = new SkillService(resolveWorkspace(request)).setSkillTags(skillName, cleaned);
        if (!updated) throw new SkillNotFoundException("Skill not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("updated", true);
        r.put("tags", cleaned);
        return r;
    }

    @GetMapping("/skills/{skill_name}/config")
    public Map<String, Object> getSkillConfig(@PathVariable("skill_name") String skillName,
                                              HttpServletRequest request) {
        Map<String, Object> manifest = new SkillService(resolveWorkspace(request)).readManifest();
        Map<String, Object> entry = SkillService.asMap(SkillService.asMap(manifest.get("skills")).get(skillName));
        if (entry == null || entry.isEmpty()) throw new SkillNotFoundException("Skill not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("config", entry.get("config") != null ? entry.get("config") : new LinkedHashMap<>());
        return r;
    }

    @PutMapping("/skills/{skill_name}/config")
    public Map<String, Object> updateSkillConfig(@PathVariable("skill_name") String skillName,
                                                 @RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        Path workspaceDir = resolveWorkspace(request);
        Map<String, Object> config = asMapOrNull(body.get("config"));
        Boolean updated = SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                SkillService.defaultWorkspaceManifest(), payload -> {
                    Map<String, Object> entry = SkillService.asMap(SkillService.asMap(payload.get("skills")).get(skillName));
                    if (entry == null || entry.isEmpty()) return false;
                    entry.put("config", config != null ? config : new LinkedHashMap<>());
                    return true;
                });
        if (!Boolean.TRUE.equals(updated)) throw new SkillNotFoundException("Skill not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("updated", true);
        return r;
    }

    @DeleteMapping("/skills/{skill_name}/config")
    public Map<String, Object> deleteSkillConfig(@PathVariable("skill_name") String skillName,
                                                 HttpServletRequest request) {
        Path workspaceDir = resolveWorkspace(request);
        Boolean updated = SkillStore.mutateJson(SkillStore.getWorkspaceSkillManifestPath(workspaceDir),
                SkillService.defaultWorkspaceManifest(), payload -> {
                    Map<String, Object> entry = SkillService.asMap(SkillService.asMap(payload.get("skills")).get(skillName));
                    if (entry == null || entry.isEmpty()) return false;
                    entry.remove("config");
                    return true;
                });
        if (!Boolean.TRUE.equals(updated)) throw new SkillNotFoundException("Skill not found");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cleared", true);
        return r;
    }

    // ------------------------------------------------------------------
    // AI optimize stream (skills_stream.py)
    // ------------------------------------------------------------------

    private static final Map<String, String> SYSTEM_PROMPTS = Map.of(
            "en", "You are an AI skill optimization expert. Please optimize the following skill content.\n"
                    + "\n## Output Format Requirements\n"
                    + "Output the skill content directly. Do NOT use code block markers (like ```yaml or ```). Do NOT add any explanations.\n"
                    + "\n## Optimization Rules\n"
                    + "1. Keep the frontmatter structure (--- enclosed header section)\n"
                    + "2. name field: lowercase with underscores\n"
                    + "3. description field: clear and concise, no more than 80 characters\n"
                    + "4. Body content: use Markdown format, well-structured\n"
                    + "5. Total length: keep within 500 characters\n"
                    + "\n---\nPlease optimize this skill:",
            "zh", "浣犳槸AI鎶€鑳戒紭鍖栦笓瀹躲€傝浼樺寲浠ヤ笅鎶€鑳藉唴瀹广€俓n"
                    + "\n## 杈撳嚭鏍煎紡瑕佹眰\n"
                    + "鐩存帴杈撳嚭鎶€鑳藉唴瀹癸紝绂佹浣跨敤浠ｇ爜鍧楁爣璁帮紙濡?```yaml 鎴?```锛夛紝绂佹娣诲姞浠讳綍瑙ｉ噴璇存槑銆俓n"
                    + "\n## 浼樺寲瑙勫垯\n"
                    + "1. 淇濇寔frontmatter缁撴瀯锛?-- 鍖呭洿鐨勫ご閮ㄥ尯鍩燂級\n"
                    + "2. name瀛楁锛氳嫳鏂囧皬鍐欎笅鍒掔嚎鍛藉悕\n"
                    + "3. description瀛楁锛氱畝娲佹竻鏅帮紝涓嶈秴杩?0瀛梊n"
                    + "4. 姝ｆ枃鐢∕arkdown鏍煎紡锛岀粨鏋勬竻鏅癨n"
                    + "5. 鎬婚暱搴︽帶鍒跺湪500瀛椾互鍐匼n"
                    + "\n---\n璇蜂紭鍖栨鎶€鑳?",
            "ru", "袙褘 褝泻褋锌械褉褌 锌芯 芯锌褌懈屑懈蟹邪褑懈懈 AI-薪邪胁褘泻芯胁. 袩芯卸邪谢褍泄褋褌邪, 芯锌褌懈屑懈蟹懈褉褍泄褌械 薪邪胁褘泻.\n"
                    + "\n## 孝褉械斜芯胁邪薪懈褟 泻 褎芯褉屑邪褌褍 胁褘胁芯写邪\n"
                    + "袙褘胁芯写懈褌械 褋芯写械褉卸懈屑芯械 薪邪胁褘泻邪 薪邪锌褉褟屑褍褞. 袧袝 懈褋锌芯谢褜蟹褍泄褌械 屑邪褉泻械褉褘 斜谢芯泻邪 泻芯写邪.\n"
                    + "\n## 袩褉邪胁懈谢邪 芯锌褌懈屑懈蟹邪褑懈懈\n"
                    + "1. 小芯褏褉邪薪懈褌械 褋褌褉褍泻褌褍褉褍 frontmatter (褉邪蟹写械谢 蟹邪谐芯谢芯胁泻邪, 蟹邪泻谢褞褔褢薪薪褘泄 胁 ---)\n"
                    + "2. 袩芯谢械 name: 褋褌褉芯褔薪褘械 斜褍泻胁褘 褋 锌芯写褔褢褉泻懈胁邪薪懈械屑\n"
                    + "3. 袩芯谢械 description: 褔褢褌泻芯械 懈 泻褉邪褌泻芯械, 薪械 斜芯谢械械 80 褋懈屑胁芯谢芯胁\n"
                    + "4. 袨褋薪芯胁薪芯械 褋芯写械褉卸懈屑芯械: 懈褋锌芯谢褜蟹褍泄褌械 褎芯褉屑邪褌 Markdown\n"
                    + "5. 袨斜褖邪褟 写谢懈薪邪: 薪械 斜芯谢械械 500 褋懈屑胁芯谢芯胁\n"
                    + "\n---\n袩芯卸邪谢褍泄褋褌邪, 芯锌褌懈屑懈蟹懈褉褍泄褌械 褝褌芯褌 薪邪胁褘泻:"
    );

    @PostMapping(value = "/skills/ai/optimize/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> aiOptimizeSkillStream(@RequestBody Map<String, Object> body) {
        String content = SkillService.str(body.get("content"));
        String language = SkillService.str(body.get("language"), "en");
        String systemPrompt = SYSTEM_PROMPTS.getOrDefault(language, SYSTEM_PROMPTS.get("en"));

        String apiKey = settingsService.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.just(json(Map.of("error",
                    "No AI model configured. Please configure in Settings.")));
        }

        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(settingsService.getBaseUrl())
                .modelName(settingsService.getModelName())
                .build();

        List<Msg> messages = List.of(
                Msg.builder().name("system").role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(systemPrompt).build()).build(),
                Msg.builder().name("user").role(MsgRole.USER)
                        .content(TextBlock.builder().text(content).build()).build());

        AtomicReference<StringBuilder> accumulated = new AtomicReference<>(new StringBuilder());
        return model.stream(messages, null, null)
                .map(response -> {
                    String text = extractText(response);
                    if (text == null || text.isEmpty()) return null;
                    StringBuilder sb = accumulated.get();
                    if (text.length() <= sb.length()) return null;
                    String delta = text.substring(sb.length());
                    sb.setLength(0);
                    sb.append(text);
                    return json(Map.of("text", delta));
                })
                .filter(Objects::nonNull)
                .concatWithValues(json(Map.of("done", true)))
                .onErrorResume(e -> {
                    log.error("AI skill optimization failed", e);
                    return Flux.just(json(Map.of("error", "Failed to optimize skill: " + e.getMessage())));
                });
    }

    private String extractText(ChatResponse response) {
        StringBuilder sb = new StringBuilder();
        if (response == null || response.getContent() == null) return "";
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Best-effort auto-update propagation after pool content changes
     * (mirrors Python's {@code _follow_auto_update}: propagate + ignore failures). */
    private void followAutoUpdate(String skillName) {
        try {
            new SkillPoolService().runPoolAutoUpdateSync(skillName);
        } catch (Exception e) {
            log.warn("auto-update follow-up failed", e);
        }
    }

    private List<String> validateTags(List<String> tags) {
        if (tags != null && tags.size() > SkillService.MAX_TAGS) {
            throw new SkillTagLimitError("At most " + SkillService.MAX_TAGS + " tags allowed");
        }
        List<String> cleaned = new ArrayList<>();
        if (tags != null) {
            for (String t : tags) {
                String s = String.valueOf(t).strip();
                if (s.length() > SkillService.MAX_TAG_LENGTH) s = s.substring(0, SkillService.MAX_TAG_LENGTH);
                if (!s.isEmpty()) cleaned.add(s);
            }
        }
        return cleaned;
    }

    private Map<String, String> parseRenameMap(String renameMap) {
        if (renameMap == null || renameMap.isBlank()) return null;
        try {
            Map<String, Object> parsed = mapper.readValue(renameMap, new TypeReference<>() {});
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : parsed.entrySet()) out.put(e.getKey(), String.valueOf(e.getValue()));
            return out;
        } catch (IOException e) {
            throw new SkillsError("rename_map must be valid JSON");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new SkillsError("No file uploaded");
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new SkillsError("Failed to read uploaded file");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMapOrNull(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        return null;
    }

    private String payloadJson(Map<String, Object> detail) {
        try {
            return mapper.writeValueAsString(detail);
        } catch (IOException e) {
            return String.valueOf(detail);
        }
    }

    private String json(Map<String, Object> data) {
        return payloadJson(data);
    }

    /** Build the 422 scan-error payload (mirrors _scan_error_payload). */
    private Map<String, Object> scanErrorPayload(SkillScanError e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "security_scan_failed");
        payload.put("detail", e.getMessage());
        payload.put("skill_name", e.skillName);
        payload.put("max_severity", maxSeverityOf(e.findings));
        payload.put("findings", e.findings);
        return payload;
    }

    private String maxSeverityOf(List<Map<String, Object>> findings) {
        for (Map<String, Object> f : findings) {
            if ("CRITICAL".equalsIgnoreCase(SkillService.str(f.get("severity")))) return "critical";
        }
        return "high";
    }

    // ── Agent-scoped skills (port of qwenpaw agent_scoped /agents/{agentId}/skills) ──
    @GetMapping("/agents/{agentId}/skills")
    public Object agentSkillList(@PathVariable String agentId, HttpServletRequest request) {
        return listSkills(withAgent(request, agentId));
    }

    @PostMapping("/agents/{agentId}/skills/refresh")
    public Object agentSkillRefresh(@PathVariable String agentId, HttpServletRequest request) {
        return refreshSkills(withAgent(request, agentId));
    }

    @PostMapping("/agents/{agentId}/skills/save")
    public Object agentSkillSave(@PathVariable String agentId,
                                 @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return saveWorkspaceSkill(body, withAgent(request, agentId));
    }

    @PostMapping("/agents/{agentId}/skills/upload")
    public Object agentSkillUpload(@PathVariable String agentId,
                                   @RequestParam("file") MultipartFile file,
                                   @RequestParam(defaultValue = "true") boolean enable,
                                   @RequestParam(defaultValue = "") String target_name,
                                   @RequestParam(defaultValue = "") String rename_map,
                                   HttpServletRequest request) {
        return uploadSkillZip(file, enable, target_name, rename_map, withAgent(request, agentId));
    }

    @DeleteMapping("/agents/{agentId}/skills/{skill_name}")
    public Object agentSkillDelete(@PathVariable String agentId, @PathVariable String skill_name,
                                   HttpServletRequest request) {
        return deleteSkill(skill_name, withAgent(request, agentId));
    }

    @PutMapping("/agents/{agentId}/skills/{skill_name}/channels")
    public Object agentSkillChannels(@PathVariable String agentId, @PathVariable String skill_name,
                                     @RequestBody List<String> channels, HttpServletRequest request) {
        return updateSkillChannels(skill_name, channels, withAgent(request, agentId));
    }

    @PutMapping("/agents/{agentId}/skills/{skill_name}/tags")
    public Object agentSkillTags(@PathVariable String agentId, @PathVariable String skill_name,
                                 @RequestBody List<String> tags, HttpServletRequest request) {
        return updateSkillTags(skill_name, tags, withAgent(request, agentId));
    }

    @GetMapping("/agents/{agentId}/skills/{skill_name}/config")
    public Object agentSkillConfig(@PathVariable String agentId, @PathVariable String skill_name,
                                   HttpServletRequest request) {
        return getSkillConfig(skill_name, withAgent(request, agentId));
    }

    @PutMapping("/agents/{agentId}/skills/{skill_name}/config")
    public Object agentSkillConfigUpdate(@PathVariable String agentId, @PathVariable String skill_name,
                                         @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return updateSkillConfig(skill_name, body, withAgent(request, agentId));
    }

    @DeleteMapping("/agents/{agentId}/skills/{skill_name}/config")
    public Object agentSkillConfigDelete(@PathVariable String agentId, @PathVariable String skill_name,
                                         HttpServletRequest request) {
        return deleteSkillConfig(skill_name, withAgent(request, agentId));
    }

    @PostMapping("/agents/{agentId}/skills/{skill_name}/disable")
    public Object agentSkillDisable(@PathVariable String agentId, @PathVariable String skill_name,
                                    HttpServletRequest request) {
        return disableSkill(skill_name, withAgent(request, agentId));
    }

    @PostMapping("/agents/{agentId}/skills/{skill_name}/enable")
    public Object agentSkillEnable(@PathVariable String agentId, @PathVariable String skill_name,
                                   HttpServletRequest request) {
        return enableSkill(skill_name, withAgent(request, agentId));
    }

    @GetMapping("/agents/{agentId}/skills/{skill_name}/files/{file_path}")
    public Object agentSkillFile(@PathVariable String agentId, @PathVariable String skill_name,
                                 @PathVariable String file_path, HttpServletRequest request) {
        return loadSkillFile(skill_name, file_path, withAgent(request, agentId));
    }

    @PostMapping("/agents/{agentId}/skills/batch-delete")
    public Object agentBatchDeleteSkills(@PathVariable String agentId,
                                         @RequestBody List<String> skills, HttpServletRequest request) {
        return batchDeleteSkills(skills, withAgent(request, agentId));
    }

    @PostMapping("/agents/{agentId}/skills/batch-disable")
    public Object agentBatchDisableSkills(@PathVariable String agentId,
                                          @RequestBody List<String> skills, HttpServletRequest request) {
        return batchDisableSkills(skills, withAgent(request, agentId));
    }

    @PostMapping("/agents/{agentId}/skills/batch-enable")
    public Object agentBatchEnableSkills(@PathVariable String agentId,
                                         @RequestBody List<String> skills, HttpServletRequest request) {
        return batchEnableSkills(skills, withAgent(request, agentId));
    }

    @GetMapping("/agents/{agentId}/skills/hub/search")
    public Object agentSkillHubSearch(@PathVariable String agentId,
                                      @RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "20") int limit) {
        return searchHub(q, limit);
    }

    @PostMapping("/agents/{agentId}/skills/hub/install/start")
    public Object agentSkillHubInstall(@PathVariable String agentId,
                                       @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return startHubInstall(body, withAgent(request, agentId));
    }

    @GetMapping("/agents/{agentId}/skills/hub/install/status/{task_id}")
    public Object agentSkillHubStatus(@PathVariable String agentId, @PathVariable String task_id) {
        return getHubInstallStatus(task_id);
    }

    @PostMapping("/agents/{agentId}/skills/hub/install/cancel/{task_id}")
    public Object agentSkillHubCancel(@PathVariable String agentId, @PathVariable String task_id) {
        return cancelHubInstall(task_id);
    }

    @GetMapping("/agents/{agentId}/skills/pool")
    public Object agentSkillPool(@PathVariable String agentId) { return listPoolSkills(); }

    @PostMapping("/agents/{agentId}/skills/pool/refresh")
    public Object agentSkillPoolRefresh(@PathVariable String agentId) { return refreshPoolSkills(); }

    @GetMapping("/agents/{agentId}/skills/pool/builtin-sources")
    public Object agentSkillPoolBuiltinSources(@PathVariable String agentId) { return listPoolBuiltinSources(); }

    @GetMapping("/agents/{agentId}/skills/pool/builtin-notice")
    public Object agentSkillPoolBuiltinNotice(@PathVariable String agentId) { return getPoolBuiltinNotice(); }

    @PostMapping("/agents/{agentId}/skills/pool/import-builtin")
    public Object agentSkillPoolImportBuiltin(@PathVariable String agentId,
                                              @RequestBody Map<String, Object> body) { return importPoolBuiltins(body); }

    @PostMapping("/agents/{agentId}/skills/pool/{skill_name}/update-builtin")
    public Object agentSkillPoolUpdateBuiltin(@PathVariable String agentId, @PathVariable String skill_name,
                                              @RequestBody(required = false) Map<String, Object> body) {
        return updatePoolBuiltin(skill_name, body);
    }

    @PostMapping("/agents/{agentId}/skills/pool/create")
    public Object agentSkillPoolCreate(@PathVariable String agentId,
                                       @RequestBody Map<String, Object> body) { return createPoolSkill(body); }

    @PutMapping("/agents/{agentId}/skills/pool/save")
    public Object agentSkillPoolSave(@PathVariable String agentId,
                                     @RequestBody Map<String, Object> body) { return savePoolSkill(body); }

    @PostMapping("/agents/{agentId}/skills/pool/upload-zip")
    public Object agentSkillPoolUploadZip(@PathVariable String agentId,
                                          @RequestParam("file") MultipartFile file,
                                          @RequestParam(defaultValue = "") String target_name,
                                          @RequestParam(defaultValue = "") String rename_map) {
        return uploadSkillPoolZip(file, target_name, rename_map);
    }

    @PostMapping("/agents/{agentId}/skills/pool/import")
    public Object agentSkillPoolImport(@PathVariable String agentId,
                                       @RequestBody Map<String, Object> body) { return importPoolSkillFromHub(body); }

    @PostMapping("/agents/{agentId}/skills/pool/upload")
    public Object agentSkillPoolUpload(@PathVariable String agentId,
                                       @RequestBody Map<String, Object> body) { return uploadWorkspaceSkillToPool(body); }

    @PostMapping("/agents/{agentId}/skills/pool/download")
    public Object agentSkillPoolDownload(@PathVariable String agentId,
                                         @RequestBody Map<String, Object> body) { return downloadPoolSkillToWorkspaces(body); }

    @DeleteMapping("/agents/{agentId}/skills/pool/{skill_name}")
    public Object agentSkillPoolDelete(@PathVariable String agentId, @PathVariable String skill_name) {
        return deletePoolSkill(skill_name);
    }

    @GetMapping("/agents/{agentId}/skills/pool/{skill_name}/config")
    public Object agentSkillPoolConfig(@PathVariable String agentId, @PathVariable String skill_name) {
        return getPoolSkillConfig(skill_name);
    }

    @PutMapping("/agents/{agentId}/skills/pool/{skill_name}/config")
    public Object agentSkillPoolConfigUpdate(@PathVariable String agentId, @PathVariable String skill_name,
                                             @RequestBody Map<String, Object> body) {
        return updatePoolSkillConfig(skill_name, body);
    }

    @DeleteMapping("/agents/{agentId}/skills/pool/{skill_name}/config")
    public Object agentSkillPoolConfigDelete(@PathVariable String agentId, @PathVariable String skill_name) {
        return deletePoolSkillConfig(skill_name);
    }

    @PutMapping("/agents/{agentId}/skills/pool/{skill_name}/tags")
    public Object agentSkillPoolTags(@PathVariable String agentId, @PathVariable String skill_name,
                                     @RequestBody List<String> tags) { return updatePoolSkillTags(skill_name, tags); }

    @PutMapping("/agents/{agentId}/skills/pool/{skill_name}/auto-update")
    public Object agentSkillPoolAutoUpdate(@PathVariable String agentId, @PathVariable String skill_name,
                                           @RequestBody Map<String, Object> body) {
        return updatePoolSkillAutoUpdate(skill_name, body);
    }

    @PostMapping("/agents/{agentId}/skills/pool/batch-delete")
    public Object agentSkillPoolBatchDelete(@PathVariable String agentId,
                                            @RequestBody List<String> skills) { return batchDeletePoolSkills(skills); }

    private HttpServletRequest withAgent(HttpServletRequest request, String agentId) {
        return new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return "X-Agent-Id".equalsIgnoreCase(name) ? agentId : super.getHeader(name);
            }
        };
    }
}