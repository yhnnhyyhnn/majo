# Plan: Java Port of QwenPaw Skills API into majo Spring Boot Backend

## Goal

Implement all `/api/skills*` endpoints in majo's Spring Boot backend (Java 21, package
`com.agent.coding`), byte-for-byte behaviorally equivalent to the QwenPaw Python reference
(`D:\code\majo\qwenpaw\app\routers\skills.py` + `D:\code\QwenPaw\src\qwenpaw\agents\skill_system\*`),
so the frontend `/skills` pages work exactly as in QwenPaw.

Reference contract sources (read-only, do not modify):
- `D:\code\majo\openapi.json` — FastAPI spec (endpoint/method inventory)
- `D:\code\majo\frontend\src\api\modules\skill.ts` — client calls + param shapes
- `D:\code\majo\frontend\src\api\types\skill.ts` — response TypeScript types (THE response contract)
- `D:\code\majo\qwenpaw\app\routers\skills.py` (1739 lines) — endpoint layer (MD5-identical to QwenPaw)
- `D:\code\QwenPaw\src\qwenpaw\agents\skill_system\{store,workspace_service,pool_service,registry,hub,models,__init__}.py` — core logic
- `D:\code\QwenPaw\src\qwenpaw\security\skill_scanner\*` — security scanner

## Environment Facts

- Frontend proxies `/api` → `http://localhost:18789` (Spring Boot). `VITE_API_BASE_URL` empty = same-origin.
- majo backend: `@RestController @RequestMapping("/api")`, port 18789, H2 file DB, Flyway, JPA.
- majo runtime workspace = `user.dir` (see `ChatController.DEFAULT_WORKSPACE`, `WorkspaceContext.get()`).
  No agent-config files exist in majo; the frontend skill pages use `X-Agent-Id` / `?agent=` but majo
  has a single implicit agent "default" whose workspace dir = `user.dir`.
- Skill pool lives at `<WORKING_DIR>/skill_pool` where `WORKING_DIR = user.dir` for majo.
- Builtin skills are packaged content dirs `<pkg>/agents/skills/{name}-{en|zh}/SKILL.md` (34 dirs).
  For the Java port they must be copied into `backend/src/main/resources/builtin-skills/`.
- No `mvn` on PATH; no maven wrapper. Compilation verification via `javac` against
  `D:\soft\java\jdk-21.0.10` + classpath from `C:\Users\qinka\.m2\repository` is impractical for full
  Spring — verify via careful review + `lsp_diagnostics` + `npm run build` for frontend contracts.
- Dependencies already present: spring-boot-starter-web (jackson), snakeyaml? (need YAML frontmatter —
  use `org.yaml:snakeyaml` which ships with Spring Boot; else add jackson-dataformat-yaml), no zip lib
  needed (java.util.zip built-in).

## Architecture (Java classes in `com.agent.coding.skill`)

Mirror Python module structure:

1. **`skill/SkillModels.java`** — DTOs mirroring `types/skill.ts`:
   - `SkillSpec`, `PoolSkillSpec`, `WorkspaceSkillSummary`, `BuiltinImportSpec`,
     `BuiltinLanguageSpec`, `BuiltinUpdateNotice`, `BuiltinRemovedSpec`,
     `HubSkillSpec`, `HubInstallTaskResponse`, `SkillSyncStatus` enum.
   - Manifest shapes: `WorkspaceManifest` (schema_version "workspace-skill-manifest.v1", version, skills),
     `PoolManifest` (schema_version "skill-pool-manifest.v1", version, skills, builtin_skill_names).
2. **`skill/SkillStore.java`** — port of `store.py`:
   - Path helpers: `getSkillPoolDir`, `getWorkspaceSkillsDir` (prefers `skills/`, legacy `skill/`), manifest paths.
   - JSON: `readJson`/`writeJsonAtomic` with file lock (`.skill.json.lock` via FileChannel) — port of `_file_write_lock`/`write_json_atomic`/`mutate_json`.
   - Frontmatter: parse SKILL.md YAML frontmatter (`---` blocks) → map; `extractVersion`; `readFrontmatterSafe`.
   - `buildSkillMetadata` (name, description, version_text, commit_text:"", source, protected, requirements{require_bins,require_envs}, updated_at=ISO mtime).
   - Path safety: `normalizeSkillDirName`, `safeSkillDir`, `_safeChildPath` (traversal + absolute + NUL checks).
   - Zip: `_extractAndValidateZip` (200MB cap, traversal check, symlink reject), `extractZipSkills`, `stagedSkillDir`.
   - `suggestConflictName` (timestamp `-YYYYMMDDHHMMSS`, strips prior suffixes, 100 attempts).
   - `readSkillFromDir`/`writeSkillToDir`/`copySkillDir` (ignore `__pycache__`, `__MACOSX`, `.DS_Store`, `Thumbs.db`, `desktop.ini`, `~*`).
   - `getSkillMtime` (ISO with Z), `computeSkillMdHash` (SHA-256 of SKILL.md), `renderSkillMd`.
3. **`skill/SkillService.java`** — port of `workspace_service.py`:
   - Constructor takes workspace dir; `listAllSkills`, `listAvailableSkills` (via resolve_effective_skills),
     `createSkill`, `saveSkill` (edit/rename/noop modes + overwrite), `deleteSkill` (only disabled deletable),
     `enableSkill`/`disableSkill` (with auto-update follow), `updateChannels`, `updateTags` (max 8, len 16),
     `get/update/deleteConfig`, `importFromZip` (rename_map, target_name, enable), `loadSkillFile`, `listSkillFiles`.
   - Workspace entry shape: `{enabled, channels: ["all"] default, source, installed_from, config, metadata{...}, requirements, updated_at}`.
4. **`skill/SkillPoolService.java`** — port of `pool_service.py`:
   - `listAllSkills`, `createSkill`, `savePoolSkill`, `deleteSkill` (protected builtins rejected),
     `setTags`, `setAutoUpdate` (enabled + targets), `importFromZip`, `importPoolSkillFromHub`,
     `uploadWorkspaceSkillToPool` (workspace_id, overwrite, preview_only), `downloadPoolSkillToWorkspaces`
     (targets/all_workspaces, overwrite, preview_only, conflict preflight), `runPoolAutoUpdateSync`.
   - Pool entry shape via `_registerPoolSkillEntry`: metadata + `external` (not primary dir), `installed_from?`,
     `config?`, `tags?`, `builtin_language?`, `builtin_source_name?`, `auto_update?/auto_update_targets?/auto_update_synced_hash?`.
   - Source classification: builtin/customized preserving existing entry intent; `isPoolBuiltinEntry`.
5. **`skill/SkillRegistry.java`** — port of `registry.py`:
   - Builtin discovery: scan `resources/builtin-skills/*-{en|zh}/SKILL.md`, `BUILTIN_SKILL_LANGUAGES=(en,zh)`,
     dir regex `^(?P<name>.+)-(?P<language>en|zh)$`; language preference from `settings.json` if present.
   - `ensureSkillPoolInitialized`, `reconcilePoolManifest`, `reconcileWorkspaceManifest`,
     `importBuiltinSkills` (conflicts list), `listBuiltinImportCandidates`, `getPoolBuiltinUpdateNotice`
     (fingerprint = sha256 of sorted builtin versions; added/missing/updated/removed),
     `updateSingleBuiltin`, `listWorkspaces` (single default for majo: agent_id "default", workspace_dir=user.dir),
     `resolveEffectiveSkills`, `getPackagedBuiltinVersions`, `getPoolBuiltinSyncStatus`.
6. **`skill/SkillHubService.java`** — port of `hub.py` (functional core):
   - `searchHubSkills(q, limit)` → GitHub repo search via REST (httpx→java.net.http.HttpClient), normalized to `HubSkillSpec`.
   - Install-task machinery: in-memory `ConcurrentHashMap` task registry with
     `HubInstallTask` (task_id=uuid, bundle_url, version, enable, status, error, result, created_at, updated_at ms),
     TTL cleanup, `installSkillFromHub` (workspace install), `importPoolSkillFromHub`.
   - Bundle resolution: GitHub URL / owner-repo / git clone (org.eclipse.jgit already in pom), local path, zip.
   - Status transitions: pending→importing→completed/failed; cancel sets cancelled.
7. **`skill/SkillScanner.java`** — port of `security/skill_scanner` (pragmatic):
   - Models: `Severity` enum (CRITICAL/HIGH/MEDIUM/LOW/INFO/SAFE), `Finding` (id, rule_id, category, severity,
     title, description, file_path, line_number, snippet, remediation, analyzer), `ScanResult`
     (skill_name, skill_path, is_safe, max_severity, findings_count, findings, scan_duration_seconds, analyzers_used, timestamp).
   - Rule-based pattern analyzer: port the shipped signature rules (command_injection, data_exfiltration,
     hardcoded_secrets, obfuscation, prompt_injection, social_engineering, supply_chain, unauthorized_tool_use)
     from `rules/signatures/*.yaml` as Java regex patterns.
   - `scanSkillDirectory` with result cache keyed on dir mtime; `SkillScanError` → router error payload shape
     (`{"error": ..., "skill_name": ..., "findings": [...]}` — confirm exact shape from `_scan_error_payload`).
8. **`skill/SkillTaskManager.java`** — hub install task lifecycle helpers (setStatus, get, cleanup, finish).
9. **`skill/SkillsController.java`** — `@RestController @RequestMapping("/api/skills")`, all endpoints listed below.

## Endpoint Inventory (methods/paths from openapi.json + router)

Workspace skills:
- GET `/skills` (agent via X-Agent-Id or `?agent=`) → `List<SkillSpec>`
- POST `/skills/refresh` → `List<SkillSpec>`
- POST `/skills` create `{name, content, config?, enable?}` → `{created, name}`
- PUT `/skills/save` `{name, content, source_name?, config?, overwrite?}` → `{success, mode: edit|rename|noop, name}`
- GET `/skills/workspaces` → `List<WorkspaceSkillSummary>`
- POST `/skills/{skill_name}/enable` / `disable` → `{updated/enabled...}` (match router)
- POST `/skills/batch-enable` / `batch-disable` / `batch-delete` (body = string[] names) → `{results: {name: {success, reason?, detail?}}}`
- DELETE `/skills/{skill_name}` → `{deleted: true}`
- GET `/skills/{skill_name}/files/{file_path}` → file content
- PUT `/skills/{skill_name}/channels` (body = string[]) → `{updated, channels}`
- PUT `/skills/{skill_name}/tags` (body = string[]) → `{updated, tags}`
- GET/PUT/DELETE `/skills/{skill_name}/config` → `{config}` / `{updated}` / `{cleared}`
- POST `/skills/upload` (multipart file, enable?, target_name?, rename_map?) → `{imported[], count, enabled, conflicts[]}`

Pool:
- GET `/skills/pool` → `List<PoolSkillSpec>`
- POST `/skills/pool/refresh` → `List<PoolSkillSpec>`
- POST `/skills/pool/create` `{name, content, config?}` → `{created, name}`
- PUT `/skills/pool/save` → `{success, mode, name}`
- POST `/skills/pool/upload-zip` (multipart file, target_name?, rename_map?) → `{imported[], count, conflicts[]}`
- POST `/skills/pool/import` `{bundle_url, version?, target_name?}` → `{installed, name, enabled, source_url}`
- POST `/skills/pool/upload` `{workspace_id, skill_name, overwrite?, preview_only?}` → `{success, name}`
- POST `/skills/pool/download` `{skill_name, targets[{workspace_id}], all_workspaces?, overwrite?, preview_only?}` → `{downloaded[], conflicts[]}`
- GET `/skills/pool/builtin-sources` → `List<BuiltinImportSpec>`
- GET `/skills/pool/builtin-notice` → `BuiltinUpdateNotice`
- POST `/skills/pool/import-builtin` `{imports[{skill_name, language}], overwrite_conflicts?}` → `{imported[], updated[], unchanged[], conflicts[]}`
- POST `/skills/pool/{skill_name}/update-builtin` `{language}` → map
- POST `/skills/pool/batch-delete` (body = string[]) → `{results}`
- DELETE `/skills/pool/{skill_name}` → `{deleted}`
- GET/PUT/DELETE `/skills/pool/{skill_name}/config` → `{config}` / `{updated}` / `{cleared}`
- PUT `/skills/pool/{skill_name}/tags` → `{updated, tags}`
- PUT `/skills/pool/{skill_name}/auto-update` `{enabled, targets}` → `{updated, enabled, targets}`

Hub:
- GET `/skills/hub/search?q&limit` → `List<HubSkillSpec>`
- POST `/skills/hub/install/start` `{bundle_url, version?, enable?, target_name?}` → `HubInstallTaskResponse`
- GET `/skills/hub/install/status/{task_id}` → `HubInstallTaskResponse`
- POST `/skills/hub/install/cancel/{task_id}` → `{task_id, status}`

AI optimize:
- POST `/skills/ai/optimize/stream` `{content, language}` → SSE `data: {text}` / `{done}` / `{error}`
  (use configured model via SettingsService + OpenAI-compatible streaming; system prompts en/zh from skills_stream.py).

## Key Behavioral Rules (from Python reference)

- Workspace skill delete: only when `enabled == false`; else error (confirm message).
- Tag validation: max 8 tags, max 16 chars each, dedupe/trim.
- Conflict handling: `suggest_conflict_name` timestamp suffix; conflicts returned as `{reason, skill_name, suggested_name}`.
- Manifest writes: file lock + atomic temp-file rename; `mutate_json` pattern (read under lock, mutate, write under lock).
- Builtin fingerprint/notice: `fingerprint` = hash over sorted builtin version texts; `has_updates` drives badge.
- `_scan_error_payload(exc)` exact JSON shape must be replicated for 4xx scan failures.
- Empty `builtin_skill_names` reset when no packaged builtins available (graceful degradation).
- All responses must match `types/skill.ts` field names exactly (camelCase as in TS).

## Implementation Steps (ordered)

1. Copy 34 builtin skill dirs → `backend/src/main/resources/builtin-skills/` (content, from QwenPaw reference).
2. Add `SkillModels.java` (DTOs + manifest containers + enums).
3. Add `SkillStore.java` (paths, JSON+lock, frontmatter, zip, path safety, metadata, mtime/hash).
4. Add `SkillService.java` (workspace CRUD + config/channels/tags/files + zip import).
5. Add `SkillPoolService.java` (pool CRUD + upload/download + auto-update + zip import + builtin interplay).
6. Add `SkillRegistry.java` (builtin scan/reconcile/notice/import/update + workspaces).
7. Add `SkillScanner.java` (severity/finding models + pattern rules + scan dir).
8. Add `SkillTaskManager.java` + `SkillHubService.java` (search + install tasks + import).
9. Add `SkillsController.java` wiring all endpoints with Spring Web (MultipartFile for uploads, SSE for stream).
10. Wire exception handling → HTTP statuses/messages matching FastAPI behavior (409 conflict, 422 scan, 400 skill errors, 404 not found).
11. Verify: `lsp_diagnostics` on all new files; `npm run build` unaffected; manual review against `skill.ts`/`types/skill.ts`.

## Verification

- Every endpoint's response JSON field set checked against `types/skill.ts` (typescript type names).
- Behavior spot-checks against router source for: delete-when-enabled, rename conflicts, tag limits, builtin notice shape, hub task status transitions, scan error payload.
- Compile check: `lsp_diagnostics` (TS/Java LSP) zero errors; if a JDK+maven classpath solution exists, attempt `javac` on skill package.

## Out of Scope (documented deviations)

- Multi-agent config (majo has one default agent; all `agent` params resolve to `user.dir`).
- Inbox events / `schedule_agent_reload` (no inbox store in majo Java) — no-op stubs where Python posts events.
- Full hub provider matrix (ClawHub/ModelScope/LobeHub/skills.sh/aliyun) — GitHub REST + git clone + local path + zip are primary; others return clear errors if unsupported.
- AI optimize uses majo's configured model (SettingsService) instead of qwenpaw's `create_model_and_formatter`.
