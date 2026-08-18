package com.agent.coding.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skills hub client (/agents/skill_system/hub.py).
 *
 * <ul>
 *   <li>{@link #searchHubSkills} — ClawHub /api/v1/search</li>
 *   <li>{@link #installSkillFromHub} — fetch bundle from a provider URL and
 *       create it in a workspace</li>
 *   <li>{@link #importPoolSkillFromHub} — same but into the skill pool</li>
 * </ul>
 *
 * Supported sources: GitHub, ClawHub (slug/detail+file API), LobeHub zip,
 * ModelScope archive zip, and direct JSON bundle URLs.
 */
public class SkillHubService {

    private static final Logger log = LoggerFactory.getLogger(SkillHubService.class);

    public static final String DEFAULT_HUB_BASE_URL = "https://clawhub.ai";
    private static final String HUB_SEARCH_PATH = "/api/v1/search";
    private static final String HUB_DETAIL_PATH = "/api/v1/skills/{slug}";
    private static final String HUB_FILE_PATH = "/api/v1/skills/{slug}/file";
    private static final String HUB_VERSION_PATH = "/api/v1/skills/{slug}/versions/{version}";
    private static final String LOBEHUB_DOWNLOAD = "https://market.lobehub.com/api/v1/skills/{id}/download";
    private static final String MODEL_SCOPE_ARCHIVE = "https://www.modelscope.cn/skills/{owner}/{name}/archive/zip/{branch}";
    private static final String QWENPAW_ARCHIVE = "https://platform.agentscope.io/skills/{owner}/{name}/archive/zip/{branch}";

    private static final int HTTP_TIMEOUT_SECONDS = 30;
    private static final int MAX_RETRIES = 3;
    private static final long BACKOFF_BASE_MS = 800;
    private static final long BACKOFF_CAP_MS = 6000;
    private static final int SKILL_PACKAGE_MAX_ENTRIES = 4096;
    private static final long SKILL_PACKAGE_MAX_BYTES = 200L * 1024 * 1024;
    private static final int GITHUB_CACHE_TTL_MS = 300_000;
    private static final int GITHUB_CACHE_MAX_ENTRIES = 500;

    private static final Set<Integer> RETRYABLE = Set.of(408, 409, 425, 429, 500, 502, 503, 504);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, CacheEntry> githubCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > GITHUB_CACHE_MAX_ENTRIES;
        }
    };

    private record CacheEntry(long timestamp, Object value) {}

    // ------------------------------------------------------------------
    // Public: search
    // ------------------------------------------------------------------

    public List<SkillModels.HubSkillSpec> searchHubSkills(String query, int limit) {
        String url = joinUrl(DEFAULT_HUB_BASE_URL, HUB_SEARCH_PATH) + "?q="
                + urlEncode(query) + "&limit=" + limit;
        Object data = httpJsonGet(url);
        List<Map<String, Object>> items = normalizeSearchItems(data);
        List<SkillModels.HubSkillSpec> results = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String slug = str(item.get("slug") != null ? item.get("slug") : item.get("name")).trim();
            if (slug.isEmpty()) continue;
            Object ownerObj = item.get("owner");
            String ownerHandle = "";
            String ownerDisplay = "";
            String ownerImage = "";
            if (ownerObj instanceof Map<?, ?> owner) {
                ownerHandle = str(owner.get("handle")).trim();
                ownerDisplay = str(owner.get("displayName")).trim();
                ownerImage = str(owner.get("image")).trim();
            }
            if (ownerHandle.isEmpty()) ownerHandle = str(item.get("ownerHandle")).trim();
            SkillModels.HubSkillSpec spec = new SkillModels.HubSkillSpec();
            spec.slug = slug;
            spec.name = str(item.get("name") != null ? item.get("name")
                    : item.get("displayName") != null ? item.get("displayName") : slug);
            spec.description = str(item.get("description") != null ? item.get("description")
                    : item.get("summary"));
            spec.version = str(item.get("version"));
            spec.sourceUrl = str(item.get("url"));
            // author / icon_url are not in the majo HubSkillSpec model; keep search parity
            results.add(spec);
        }
        return results;
    }

    private List<Map<String, Object>> normalizeSearchItems(Object data) {
        if (data instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) if (o instanceof Map<?, ?> m) out.add(stringKeyMap(m));
            return out;
        }
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> m = stringKeyMap(map);
            for (String key : List.of("items", "skills", "results", "data")) {
                Object v = m.get(key);
                if (v instanceof List<?> list) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Object o : list) if (o instanceof Map<?, ?> mm) out.add(stringKeyMap(mm));
                    return out;
                }
            }
            if (m.containsKey("name") && m.containsKey("slug")) return List.of(m);
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Public: install
    // ------------------------------------------------------------------

    public record HubInstallResult(String name, boolean enabled, String sourceUrl, String installedFrom) {}

    /**
     * Install a skill from a hub URL into a workspace.
     * Mirrors hub.py install_skill_from_hub.
     */
    public HubInstallResult installSkillFromHub(Path workspaceDir, String bundleUrl,
                                                String version, boolean enable,
                                                String targetName, AtomicBoolean cancelChecker) {
        Bundle bundle = prepareInstallPayload(bundleUrl, version, targetName, cancelChecker);
        ensureNotCancelled(cancelChecker);
        SkillService skillService = new SkillService(workspaceDir);
        String created = skillService.createSkill(bundle.name, bundle.content,
                bundle.references, bundle.scripts, bundle.extraFiles,
                null, enable, bundle.installedFrom, "customized");
        if (created == null) throw new SkillConflictError(buildHubConflict(bundle.name));
        ensureNotCancelled(cancelChecker);
        boolean enabled = false;
        if (enable) {
            Map<String, Object> enableResult = skillService.enableSkill(created, null);
            enabled = Boolean.TRUE.equals(enableResult.get("success"));
            if (!enabled) log.warn("Skill '{}' imported but enable failed", created);
        }
        return new HubInstallResult(created, enabled, bundle.sourceUrl, bundle.installedFrom);
    }

    /** Install a skill from a hub URL into the pool (no enable). */
    public HubInstallResult importPoolSkillFromHub(String bundleUrl, String version,
                                                   String targetName, AtomicBoolean cancelChecker) {
        Bundle bundle = prepareInstallPayload(bundleUrl, version, targetName, cancelChecker);
        ensureNotCancelled(cancelChecker);
        SkillPoolService poolService = new SkillPoolService();
        String created = poolService.createSkill(bundle.name, bundle.content,
                bundle.references, bundle.scripts, null, bundle.installedFrom);
        if (created == null) throw new SkillConflictError(buildHubConflict(bundle.name));
        return new HubInstallResult(created, false, bundle.sourceUrl, bundle.installedFrom);
    }

    // ------------------------------------------------------------------
    // Bundle preparation
    // ------------------------------------------------------------------

    private record Bundle(String name, String content, Map<String, Object> references,
                          Map<String, Object> scripts, Map<String, Object> extraFiles,
                          String sourceUrl, String installedFrom) {}

    private Bundle prepareInstallPayload(String bundleUrl, String version,
                                         String targetName, AtomicBoolean cancelChecker) {
        if (bundleUrl == null || !isHttpUrl(bundleUrl)) {
            throw new SkillsError("bundle_url must be a valid http(s) URL");
        }
        ensureNotCancelled(cancelChecker);
        FetchedBundle fetched = resolveBundleFromUrl(bundleUrl, version, cancelChecker);
        String installedFrom = classifyInstallOrigin(bundleUrl);
        String name = fetched.name();
        String content = fetched.content();
        Map<String, Object> references = fetched.references();
        Map<String, Object> scripts = fetched.scripts();
        Map<String, Object> extraFiles = fetched.extraFiles();
        if (name == null || name.isBlank()) {
            String pathPart = URI.create(bundleUrl).getPath().replaceAll("/$", "");
            int idx = pathPart.lastIndexOf('/');
            name = safeFallbackName(idx >= 0 ? pathPart.substring(idx + 1) : pathPart);
        }
        name = sanitizeSkillDirName(name);
        String normalizedTarget = targetName == null ? "" : targetName.trim();
        if (!normalizedTarget.isEmpty()) {
            name = sanitizeSkillDirName(normalizedTarget);
        }
        return new Bundle(name, content, references, scripts, extraFiles,
                fetched.sourceUrl(), installedFrom);
    }

    private record FetchedBundle(String name, String content,
                                 Map<String, Object> references, Map<String, Object> scripts,
                                 Map<String, Object> extraFiles, String sourceUrl) {}

    private FetchedBundle resolveBundleFromUrl(String bundleUrl, String version,
                                               AtomicBoolean cancelChecker) {
        String host = URI.create(bundleUrl).getHost().toLowerCase();
        String path = URI.create(bundleUrl).getPath();

        if (host.contains("skills.sh") || host.contains("www.skills.sh")) {
            String[] spec = extractSkillsShSpec(bundleUrl);
            if (spec == null) throw new SkillsError("Invalid skills.sh URL format");
            String owner = spec[0], repo = spec[1], skill = spec[2];
            String branch = defaultBranch(owner, repo);
            Bundle bundle = fetchBundleFromRepoAndSkillHint(owner, repo, skill, version, branch, cancelChecker);
            return new FetchedBundle(skill, bundle.content(), bundle.references(), bundle.scripts(),
                    bundle.extraFiles(), "https://github.com/" + owner + "/" + repo);
        }
        if (host.equals("github.com") || host.equals("www.github.com")) {
            String[] spec = extractGithubSpec(bundleUrl);
            if (spec == null) throw new SkillsError(
                    "Invalid GitHub URL format. Use a repo or path URL, e.g. "
                    + "https://github.com/owner/repo or "
                    + "https://github.com/owner/repo/tree/branch/path/to/skill");
            String owner = spec[0], repo = spec[1], branchInUrl = spec[2], pathHint = spec[3];
            pathHint = pathHint.replaceAll("^/+", "").replaceAll("/+$", "");
            if (pathHint.endsWith("/SKILL.md")) pathHint = pathHint.substring(0, pathHint.length() - "/SKILL.md".length());
            else if (pathHint.equals("SKILL.md")) pathHint = "";
            String branch = version.trim().isEmpty() ? branchInUrl.trim() : version.trim();
            String defaultBranch = "main";
            try { defaultBranch = defaultBranch(owner, repo); } catch (Exception ignored) {}
            Bundle bundle = fetchBundleFromRepoAndSkillHint(owner, repo, pathHint,
                    branch.isEmpty() ? defaultBranch : branch, defaultBranch, cancelChecker);
            return new FetchedBundle(bundle.name(), bundle.content(), bundle.references(),
                    bundle.scripts(), bundle.extraFiles(), "https://github.com/" + owner + "/" + repo);
        }
        if (host.contains("clawhub.ai")) {
            String slug = extractClawhubSlug(bundleUrl);
            if (slug.isEmpty()) throw new SkillsError("Invalid ClawHub URL format");
            return fetchBundleFromClawhub(slug, version, cancelChecker);
        }
        if (host.equals("lobehub.com") || host.equals("www.lobehub.com")
                || host.equals("market.lobehub.com")) {
            String identifier = extractLobehubIdentifier(bundleUrl);
            if (identifier.isEmpty()) throw new SkillsError("Invalid LobeHub skill URL format");
            return fetchLobehubZip(identifier, version, cancelChecker);
        }
        if (host.equals("modelscope.cn") || host.equals("www.modelscope.cn")) {
            String[] spec = extractModelScopeSpec(bundleUrl);
            if (spec == null) throw new SkillsError(
                    "Invalid ModelScope URL format. Use URL like https://modelscope.cn/skills/@owner/skill-name");
            return fetchArchiveZip(MODEL_SCOPE_ARCHIVE
                    .replace("{owner}", urlEncode(spec[0]))
                    .replace("{name}", urlEncode(spec[1]))
                    .replace("{branch}", urlEncode(version.trim().isEmpty() ? spec[2] : version.trim())),
                    spec[1], "ModelScope archive download failed", cancelChecker);
        }
        if (host.equals("platform.agentscope.io")) {
            String[] spec = extractModelScopeSpec(bundleUrl);
            if (spec == null) throw new SkillsError(
                    "Invalid Majo URL format. Use URL like https://platform.agentscope.io/skills/@owner/skill-name");
            return fetchArchiveZip(QWENPAW_ARCHIVE
                    .replace("{owner}", urlEncode(spec[0]))
                    .replace("{name}", urlEncode(spec[1]))
                    .replace("{branch}", urlEncode(version.trim().isEmpty() ? spec[2] : version.trim())),
                    spec[1], "Majo archive download failed", cancelChecker);
        }
        if (host.equals("api.aliyun.com") || host.equals("www.api.aliyun.com")) {
            throw new SkillsError("Aliyun AgentExplorer import requires the Aliyun SDK, which is not "
                    + "available in this build. Import from the underlying GitHub / ClawHub source instead.");
        }
        if (host.contains("skillsmp.com") || host.contains("www.skillsmp.com")) {
            throw new SkillsError("skillsmp.com import is not supported in this build; "
                    + "use the GitHub source URL instead.");
        }
        // Fallback: treat as a direct bundle JSON URL.
        Object data = httpJsonGet(bundleUrl);
        return normalizeBundle(data, bundleUrl, cancelChecker);
    }

    private String classifyInstallOrigin(String bundleUrl) {
        if (bundleUrl == null || bundleUrl.isBlank()) return "";
        String host = URI.create(bundleUrl).getHost().toLowerCase();
        if (host.contains("skills.sh")) return "skills-sh";
        if (host.equals("github.com") || host.equals("www.github.com")) return "github";
        if (host.contains("clawhub.ai")) return "clawhub";
        if (host.contains("lobehub.com")) return "lobehub";
        if (host.contains("modelscope.cn")) return "modelscope";
        if (host.equals("platform.agentscope.io")) return "qwenpaw";
        if (host.contains("api.aliyun.com")) return "aliyun";
        if (host.contains("skillsmp.com")) return "skillsmp";
        return "url";
    }

    // ------------------------------------------------------------------
    // GitHub-backed fetchers
    // ------------------------------------------------------------------

    private Bundle fetchBundleFromRepoAndSkillHint(String owner, String repo, String skillHint,
                                                   String branch, String defaultBranch,
                                                   AtomicBoolean cancelChecker) {
        List<String> branchCandidates = new ArrayList<>();
        if (versionNonEmpty(branch)) branchCandidates.add(branch.trim());
        if (defaultBranch != null && !defaultBranch.isEmpty() && !branchCandidates.contains(defaultBranch))
            branchCandidates.add(defaultBranch);
        for (String b : List.of("main", "master")) {
            if (!branchCandidates.contains(b)) branchCandidates.add(b);
        }
        String skill = skillHint == null ? "" : skillHint.trim();

        String selectedRoot = "";
        Map<String, Object> skillMdEntry = null;
        String usedBranch = branchCandidates.get(0);
        for (String candidate : branchCandidates) {
            usedBranch = candidate;
            List<String> roots = new ArrayList<>();
            if (!skill.isEmpty()) roots.add("skills/" + skill);
            roots.add(skill);
            roots.add("");
            for (String root : roots) {
                String skillMdPath = root.isEmpty() ? "SKILL.md" : root + "/SKILL.md";
                try {
                    Map<String, Object> entry = githubGetContentEntry(owner, repo, skillMdPath, usedBranch);
                    if ("file".equals(str(entry.get("type")))) {
                        selectedRoot = root;
                        skillMdEntry = entry;
                        break;
                    }
                } catch (SkillsError e) {
                    if (!e.getMessage().contains("404")) throw e;
                }
            }
            if (skillMdEntry != null) break;
        }

        if (skillMdEntry == null) {
            String skillNorm = normalizeSkillKey(skill);
            for (String candidate : branchCandidates) {
                usedBranch = candidate;
                List<String> roots = githubListSkillMdRoots(owner, repo, usedBranch);
                for (String root : roots) {
                    String leaf = root.isEmpty() ? "" : root.substring(root.lastIndexOf('/') + 1);
                    String leafNorm = normalizeSkillKey(leaf);
                    if (leafNorm.isEmpty()) continue;
                    if (skillNorm.isEmpty() || leafNorm.equals(skillNorm)
                            || leafNorm.contains(skillNorm) || skillNorm.contains(leafNorm)
                            || skillNorm.endsWith("-" + leafNorm)) {
                        selectedRoot = root;
                        String skillMdPath = root.isEmpty() ? "SKILL.md" : root + "/SKILL.md";
                        try {
                            Map<String, Object> entry = githubGetContentEntry(owner, repo, skillMdPath, usedBranch);
                            if ("file".equals(str(entry.get("type")))) {
                                skillMdEntry = entry;
                                break;
                            }
                        } catch (SkillsError ignored) {}
                    }
                }
                if (skillMdEntry != null) break;
            }
        }

        if (skillMdEntry == null) {
            throw new SkillsError("Could not find SKILL.md in source repository "
                    + "https://github.com/" + owner + "/" + repo + ". "
                    + "Path hint: '" + skill + "'; tried branches: " + branchCandidates + ". "
                    + "Ensure the URL points to a folder containing SKILL.md, e.g. "
                    + "https://github.com/owner/repo/tree/master/skills/skill-name");
        }

        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", githubReadFile(skillMdEntry));
        files.putAll(githubCollectTreeFiles(owner, repo, usedBranch, selectedRoot, cancelChecker));
        String sourceUrl = "https://github.com/" + owner + "/" + repo;
        String skillName = skill.isEmpty() ? repo : skill;
        return bundleFromFiles(skillName, files, sourceUrl);
    }

    private String defaultBranch(String owner, String repo) {
        return githubCachedCall("default_branch:" + owner + "/" + repo, () -> {
            Object data = httpJsonGet("https://api.github.com/repos/" + owner + "/" + repo);
            if (data instanceof Map<?, ?> m) {
                Object raw = m.get("default_branch");
                if (raw instanceof String s && !s.isBlank()) return s.trim();
            }
            return "main";
        });
    }

    private List<String> githubListSkillMdRoots(String owner, String repo, String ref) {
        return githubCachedCall("skill_md_roots:" + owner + "/" + repo + "/" + ref, () -> {
            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/git/trees/" + ref + "?recursive=1";
            Object data;
            try {
                data = httpJsonGet(url);
            } catch (SkillsError e) {
                if (e.getMessage().contains("404")) return List.of();
                throw e;
            }
            if (!(data instanceof Map<?, ?> m)) return List.of();
            Object tree = m.get("tree");
            if (!(tree instanceof List<?> list)) return List.of();
            Set<String> seen = new LinkedHashSet<>();
            List<String> roots = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> item)) continue;
                Object p = item.get("path");
                if (!(p instanceof String path)) continue;
                if (path.equals("SKILL.md")) roots.add("");
                else if (path.endsWith("/SKILL.md")) roots.add(path.substring(0, path.length() - "/SKILL.md".length()));
            }
            List<String> unique = new ArrayList<>();
            for (String r : roots) if (seen.add(r)) unique.add(r);
            return unique;
        });
    }

    private Map<String, Object> githubGetContentEntry(String owner, String repo, String path, String ref) {
        return githubCachedCall("content:" + owner + "/" + repo + "/" + path + "@" + ref, () -> {
            String encoded = encodePath(path);
            String url = "https://api.github.com/repos/" + owner + "/" + repo
                    + "/contents" + (encoded.isEmpty() ? "" : "/" + encoded) + "?ref=" + urlEncode(ref);
            Object data = httpJsonGet(url);
            if (!(data instanceof Map<?, ?> m)) throw new SkillsError("Unexpected GitHub response for path: " + path);
            return stringKeyMap(m);
        });
    }

    private List<Map<String, Object>> githubListDirEntries(String owner, String repo, String path, String ref) {
        return githubCachedCall("dir:" + owner + "/" + repo + "/" + path + "@" + ref, () -> {
            String encoded = encodePath(path);
            String url = "https://api.github.com/repos/" + owner + "/" + repo
                    + "/contents" + (encoded.isEmpty() ? "" : "/" + encoded) + "?ref=" + urlEncode(ref);
            Object data = httpJsonGet(url);
            List<Map<String, Object>> out = new ArrayList<>();
            if (data instanceof List<?> list) {
                for (Object o : list) if (o instanceof Map<?, ?> m) out.add(stringKeyMap(m));
            }
            return out;
        });
    }

    private Map<String, String> githubCollectTreeFiles(String owner, String repo, String ref,
                                                       String root, AtomicBoolean cancelChecker) {
        Map<String, String> files = new LinkedHashMap<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(root == null ? "" : root);
        int visited = 0;
        while (!pending.isEmpty()) {
            ensureNotCancelled(cancelChecker);
            String current = pending.pop();
            for (Map<String, Object> entry : githubListDirEntries(owner, repo, current, ref)) {
                ensureNotCancelled(cancelChecker);
                String type = str(entry.get("type"));
                String entryPath = str(entry.get("path"));
                if (entryPath.isEmpty()) continue;
                if ("dir".equals(type)) {
                    pending.push(entryPath);
                    continue;
                }
                if (!"file".equals(type)) continue;
                String rel = relativeFromRoot(entryPath, root);
                files.put(rel, githubReadFile(entry));
                visited++;
                if (visited >= SKILL_PACKAGE_MAX_ENTRIES) return files;
            }
        }
        return files;
    }

    private String githubReadFile(Map<String, Object> entry) {
        Object dl = entry.get("download_url");
        if (dl instanceof String s && !s.isEmpty()) return httpTextGet(s);
        Object content = entry.get("content");
        if (content instanceof String s && !s.isEmpty()) {
            try {
                String normalized = s.replace("\n", "");
                return new String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        throw new SkillsError("Unable to read file content from GitHub entry");
    }

    // ------------------------------------------------------------------
    // ClawHub fetcher
    // ------------------------------------------------------------------

    private FetchedBundle fetchBundleFromClawhub(String slug, String version, AtomicBoolean cancelChecker) {
        String base = DEFAULT_HUB_BASE_URL;
        String detailUrl = joinUrl(base, HUB_DETAIL_PATH.replace("{slug}", slug));
        Object data = httpJsonGet(detailUrl);
        // hydrate: fetch file contents from version files list
        Map<String, String> files = new LinkedHashMap<>();
        String displayName = slug;
        if (data instanceof Map<?, ?> root) {
            Object skillObj = root.get("skill");
            Map<String, Object> skill = skillObj instanceof Map<?, ?> m ? stringKeyMap(m) : Map.of();
            String sSlug = str(skill.get("slug")).trim();
            if (sSlug.isEmpty()) sSlug = slug;
            Object display = skill.get("displayName");
            if (display instanceof String s && !s.isBlank()) displayName = s;
            else displayName = sSlug;

            // find version files
            Object versionObj = root.get("version");
            String versionStr = str(root.get("version") != null ? root.get("version") : "");
            if (versionObj instanceof Map<?, ?> v) {
                versionStr = str(v.get("version")).isEmpty() ? versionStr : str(v.get("version"));
            }
            List<Object> fileList = filesListOf(stringKeyMap(root));
            for (Object o : fileList) {
                if (!(o instanceof Map<?, ?> fm)) continue;
                String path = str(fm.get("path"));
                if (path.isEmpty()) continue;
                String fileUrl = joinUrl(base, HUB_FILE_PATH.replace("{slug}", sSlug))
                        + "?path=" + urlEncode(path);
                if (!versionStr.isEmpty()) fileUrl += "&version=" + urlEncode(versionStr);
                try {
                    files.put(path, httpTextGet(fileUrl));
                } catch (Exception e) {
                    log.warn("Failed to fetch hub file {}: {}", path, e.getMessage());
                }
            }
            // if no files hydatable, try a content/skill_md field
            if (files.isEmpty()) {
                String content = str(root.get("content") != null ? root.get("content")
                        : root.get("skill_md") != null ? root.get("skill_md") : root.get("skillMd"));
                if (!content.isEmpty()) files.put("SKILL.md", content);
            }
        }
        if (files.isEmpty()) throw new SkillsError("Failed to fetch skill files from hub: " + slug);
        Bundle b = bundleFromFiles(displayName, files, detailUrl);
        return new FetchedBundle(b.name(), b.content(), b.references(), b.scripts(), b.extraFiles(), detailUrl);
    }

    private List<Object> filesListOf(Map<String, Object> root) {
        Object versionObj = root.get("version");
        if (versionObj instanceof Map<?, ?> v) {
            Object f = v.get("files");
            if (f instanceof List<?> list) {
                List<Object> out = new ArrayList<>();
                out.addAll(list);
                return out;
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // LobeHub zip fetcher
    // ------------------------------------------------------------------

    private FetchedBundle fetchLobehubZip(String identifier, String version, AtomicBoolean cancelChecker) {
        String url = LOBEHUB_DOWNLOAD.replace("{id}", urlEncode(identifier));
        if (version != null && !version.trim().isEmpty()) url += "?version=" + urlEncode(version.trim());
        byte[] payload = httpBytesGet(url, null, null);
        Map<String, String> files = new LinkedHashMap<>();
        long totalBytes = 0;
        int entryCount = 0;
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(payload))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                entryCount++;
                if (entryCount > SKILL_PACKAGE_MAX_ENTRIES)
                    throw new SkillsError("LobeHub skill package has too many files");
                totalBytes += Math.max(0, entry.getSize());
                if (totalBytes > SKILL_PACKAGE_MAX_BYTES)
                    throw new SkillsError("LobeHub skill package is too large to import");
                List<String> parts = safePathParts(entry.getName().replace("\\", "/"));
                if (parts == null) continue;
                if (!shouldKeepLobehubFile(parts)) continue;
                String rel = String.join("/", parts);
                byte[] raw = zis.readAllBytes();
                if (!isProbablyTextBlob(raw)) continue;
                files.put(rel, new String(raw, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new SkillsError("LobeHub skill download did not return a valid zip");
        }
        if (!files.containsKey("SKILL.md"))
            throw new SkillsError("LobeHub skill package is missing SKILL.md");
        String skillName = identifier;
        Map<String, Object> post = SkillStore.readFrontmatterFromContent(files.get("SKILL.md"));
        Object fmName = post.get("name");
        if (fmName instanceof String s && !s.isBlank()) skillName = s.trim();
        Bundle b = bundleFromFiles(skillName, files, url);
        return new FetchedBundle(b.name(), b.content(), b.references(), b.scripts(), b.extraFiles(), url);
    }

    private boolean shouldKeepLobehubFile(List<String> parts) {
        if (parts.isEmpty()) return false;
        if (parts.size() == 1) return parts.get(0).equals("SKILL.md") || true;
        if (parts.get(0).equals("references") || parts.get(0).equals("scripts")) return parts.size() > 1;
        return true;
    }

    private List<String> safePathParts(String path) {
        if (path == null || path.isBlank() || path.startsWith("/")) return null;
        String[] raw = path.split("/");
        List<String> parts = new ArrayList<>();
        for (String p : raw) if (!p.isEmpty()) parts.add(p);
        if (parts.isEmpty()) return null;
        for (String p : parts) if (p.equals(".") || p.equals("..")) return null;
        return parts;
    }

    private boolean isProbablyTextBlob(byte[] payload) {
        if (payload.length == 0) return true;
        for (byte b : payload) if (b == 0) return false;
        int sample = Math.min(payload.length, 1024);
        int nonText = 0;
        for (int i = 0; i < sample; i++) {
            byte b = payload[i];
            int ub = b & 0xFF;
            if (ub < 0x20 && ub != 7 && ub != 8 && ub != 9 && ub != 10 && ub != 12 && ub != 13 && ub != 27) {
                nonText++;
            }
        }
        return nonText <= Math.max(1, sample / 10);
    }

    // ------------------------------------------------------------------
    // ModelScope archive zip fetcher
    // ------------------------------------------------------------------

    private FetchedBundle fetchArchiveZip(String url, String fallbackName, String errorPrefix,
                                          AtomicBoolean cancelChecker) {
        byte[] payload = httpBytesGet(url, null, null);
        Map<String, String> files = new LinkedHashMap<>();
        long totalBytes = 0;
        int entryCount = 0;
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(payload))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                entryCount++;
                if (entryCount > SKILL_PACKAGE_MAX_ENTRIES)
                    throw new SkillsError("Archive has too many files");
                totalBytes += Math.max(0, entry.getSize());
                if (totalBytes > SKILL_PACKAGE_MAX_BYTES)
                    throw new SkillsError("Archive is too large to import");
                List<String> parts = safePathParts(entry.getName().replace("\\", "/"));
                if (parts == null || parts.size() < 2) continue;
                String rel = String.join("/", parts.subList(1, parts.size()));
                byte[] raw = zis.readAllBytes();
                if (!isProbablyTextBlob(raw)) continue;
                files.put(rel, new String(raw, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new SkillsError(errorPrefix + ": archive is not a valid zip");
        }
        if (!files.containsKey("SKILL.md"))
            throw new SkillsError("Archive is missing SKILL.md");
        String name = fallbackName;
        Map<String, Object> post = SkillStore.readFrontmatterFromContent(files.get("SKILL.md"));
        Object fmName = post.get("name");
        if (fmName instanceof String s && !s.isBlank()) name = s.trim();
        Bundle b = bundleFromFiles(name, files, url);
        return new FetchedBundle(b.name(), b.content(), b.references(), b.scripts(), b.extraFiles(), url);
    }

    // ------------------------------------------------------------------
    // Bundle normalization
    // ------------------------------------------------------------------

    private FetchedBundle normalizeBundle(Object data, String sourceUrl, AtomicBoolean cancelChecker) {
        Map<String, Object> payload;
        if (data instanceof Map<?, ?> m) {
            Map<String, Object> sm = stringKeyMap(m);
            if (sm.get("skill") instanceof Map<?, ?> sk && !bundleHasContent(sm)) payload = stringKeyMap(sk);
            else payload = sm;
        } else {
            throw new SkillsError("Hub bundle is not a valid JSON object");
        }

        String content = str(payload.get("content") != null ? payload.get("content")
                : payload.get("skill_md") != null ? payload.get("skill_md")
                : payload.get("skillMd") != null ? payload.get("skillMd") : "");
        Map<String, Object> references = sanitizeTree(payload.get("references"));
        Map<String, Object> scripts = sanitizeTree(payload.get("scripts"));
        Map<String, Object> extraFiles = new LinkedHashMap<>();

        Object filesObj = payload.get("files");
        if (filesObj instanceof Map<?, ?> fm) {
            Map<String, Object> files = stringKeyMap(fm);
            if (references.isEmpty()) {
                Map<String, Object> r2 = new LinkedHashMap<>();
                Map<String, Object> s2 = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : files.entrySet()) {
                    if (!(e.getValue() instanceof String)) continue;
                    List<String> parts = safePathParts(e.getKey());
                    if (parts == null || parts.isEmpty()) continue;
                    if (parts.get(0).equals("references") && parts.size() > 1) treeInsert(r2, parts.subList(1, parts.size()), (String) e.getValue());
                    else if (parts.get(0).equals("scripts") && parts.size() > 1) treeInsert(s2, parts.subList(1, parts.size()), (String) e.getValue());
                }
                references = r2;
                scripts = s2;
            }
            for (Map.Entry<String, Object> e : files.entrySet()) {
                if (!(e.getValue() instanceof String)) continue;
                if (e.getKey().equals("SKILL.md")) continue;
                List<String> parts = safePathParts(e.getKey());
                if (parts == null || parts.isEmpty()) continue;
                if (parts.get(0).equals("references") || parts.get(0).equals("scripts")) continue;
                treeInsert(extraFiles, parts, (String) e.getValue());
            }
            Object md = files.get("SKILL.md");
            if (content.isEmpty() && md instanceof String s) content = s;
        }

        if (content.isEmpty()) throw new SkillsError("Hub bundle missing SKILL.md content");
        String name = str(payload.get("name"));
        if (name.isEmpty()) {
            Map<String, Object> post = SkillStore.readFrontmatterFromContent(content);
            name = str(post.get("name"));
        }
        if (name.isEmpty()) throw new SkillsError("Hub bundle missing skill name");
        return new FetchedBundle(name, content, references, scripts, extraFiles, sourceUrl);
    }

    private boolean bundleHasContent(Map<String, Object> payload) {
        String content = str(payload.get("content") != null ? payload.get("content")
                : payload.get("skill_md") != null ? payload.get("skill_md")
                : payload.get("skillMd") != null ? payload.get("skillMd") : "");
        if (!content.isBlank()) return true;
        Object files = payload.get("files");
        if (files instanceof Map<?, ?> fm) {
            Object md = fm.get("SKILL.md");
            if (md instanceof String s) return true;
        }
        return false;
    }

    private Bundle bundleFromFiles(String fallbackName, Map<String, String> files, String sourceUrl) {
        String content = files.getOrDefault("SKILL.md", "");
        Map<String, Object> references = new LinkedHashMap<>();
        Map<String, Object> scripts = new LinkedHashMap<>();
        Map<String, Object> extraFiles = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getKey().equals("SKILL.md")) continue;
            List<String> parts = safePathParts(e.getKey());
            if (parts == null || parts.isEmpty()) continue;
            if (parts.get(0).equals("references") && parts.size() > 1) {
                treeInsert(references, parts.subList(1, parts.size()), e.getValue());
            } else if (parts.get(0).equals("scripts") && parts.size() > 1) {
                treeInsert(scripts, parts.subList(1, parts.size()), e.getValue());
            } else {
                treeInsert(extraFiles, parts, e.getValue());
            }
        }
        String name = fallbackName;
        Map<String, Object> post = SkillStore.readFrontmatterFromContent(content);
        Object fmName = post.get("name");
        if (fmName instanceof String s && !s.isBlank()) name = s.trim();
        return new Bundle(name, content, references, scripts, extraFiles, sourceUrl, "");
    }

    private Map<String, Object> sanitizeTree(Object tree) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(tree instanceof Map<?, ?> m)) return out;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            if (key.equals(".") || key.equals("..") || key.contains("/") || key.contains("\\")) continue;
            if (e.getValue() instanceof Map<?, ?> child) out.put(key, sanitizeTree(child));
            else if (e.getValue() instanceof String s) out.put(key, s);
        }
        return out;
    }

    private void treeInsert(Map<String, Object> tree, List<String> parts, String content) {
        Map<String, Object> node = tree;
        for (int i = 0; i < parts.size() - 1; i++) {
            Object child = node.get(parts.get(i));
            if (!(child instanceof Map<?, ?> cm)) {
                Map<String, Object> nc = new LinkedHashMap<>();
                node.put(parts.get(i), nc);
                node = nc;
            } else {
                node = stringKeyMap(cm);
            }
        }
        node.put(parts.get(parts.size() - 1), content);
    }

    // ------------------------------------------------------------------
    // Text & name helpers
    // ------------------------------------------------------------------

    private String safeFallbackName(String raw) {
        String out = raw.replaceAll("[^a-zA-Z0-9_-]", "-").replaceAll("^[-_]+|[-_]+$", "");
        return out.isEmpty() ? "imported-skill" : out;
    }

    private String normalizeSkillKey(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^[-]+|[-]+$", "");
    }

    private String sanitizeSkillDirName(String name) {
        if (name == null || name.isBlank()) return "imported-skill";
        if (name.contains("/") || name.contains("\\")) {
            String sanitized = normalizeSkillKey(name);
            return sanitized.isEmpty() ? safeFallbackName(name) : sanitized;
        }
        return name;
    }

    private boolean isHttpUrl(String text) {
        try {
            URI uri = URI.create(text.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureNotCancelled(AtomicBoolean checker) {
        if (checker != null && checker.get()) throw new SkillImportCancelled("Skill import cancelled by user");
    }

    /** Raised internally when the cancel event is set. */
    public static class SkillImportCancelled extends RuntimeException {
        public SkillImportCancelled(String message) { super(message); }
    }

    private String buildHubConflict(String name) {
        String suggested = SkillStore.suggestConflictName(name, null);
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("reason", "conflict");
        conflict.put("skill_name", name);
        conflict.put("suggested_name", suggested);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", "conflict");
        detail.put("skill_name", name);
        detail.put("suggested_name", suggested);
        detail.put("conflicts", List.of(conflict));
        detail.put("message", "Failed to create skill '" + name + "'. This skill already exists.");
        // store detail via a dedicated exception carrying the payload
        throw new SkillConflictErrorDetail(name, detail);
    }

    /** SkillConflictError carrying the structured conflict payload. */
    public static class SkillConflictErrorDetail extends SkillConflictError {
        public final Map<String, Object> detail;
        public SkillConflictErrorDetail(String message, Map<String, Object> detail) {
            super(message);
            this.detail = detail;
        }
    }

    // ------------------------------------------------------------------
    // URL matchers
    // ------------------------------------------------------------------

    private String extractClawhubSlug(String url) {
        URI uri = URI.create(url);
        if (!uri.getHost().contains("clawhub.ai")) return "";
        String path = uri.getPath().replaceAll("/+$", "");
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1).trim() : "";
    }

    private String[] extractSkillsShSpec(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost().toLowerCase();
        if (!host.equals("skills.sh") && !host.equals("www.skills.sh")) return null;
        String[] parts = uri.getPath().split("/");
        List<String> nonEmpty = new ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) nonEmpty.add(p);
        if (nonEmpty.size() < 3) return null;
        return new String[]{nonEmpty.get(0), nonEmpty.get(1), nonEmpty.get(2)};
    }

    private String[] extractGithubSpec(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost().toLowerCase();
        if (!host.equals("github.com") && !host.equals("www.github.com")) return null;
        String[] parts = uri.getPath().split("/");
        List<String> nonEmpty = new ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) nonEmpty.add(p);
        if (nonEmpty.size() < 2) return null;
        String owner = nonEmpty.get(0), repo = nonEmpty.get(1);
        String branch = "";
        String pathHint = "";
        if (nonEmpty.size() >= 4 && (nonEmpty.get(2).equals("tree") || nonEmpty.get(2).equals("blob"))) {
            branch = nonEmpty.get(3);
            if (nonEmpty.size() > 4) pathHint = String.join("/", nonEmpty.subList(4, nonEmpty.size()));
        } else if (nonEmpty.size() > 2) {
            pathHint = String.join("/", nonEmpty.subList(2, nonEmpty.size()));
        }
        return new String[]{owner, repo, branch, pathHint};
    }

    private String extractLobehubIdentifier(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost().toLowerCase();
        String[] parts = uri.getPath().split("/");
        List<String> nonEmpty = new ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) nonEmpty.add(p);
        if (host.equals("lobehub.com") || host.equals("www.lobehub.com")) {
            for (int i = 0; i < nonEmpty.size(); i++) {
                if (nonEmpty.get(i).equals("skills") && i + 1 < nonEmpty.size()) {
                    return nonEmpty.get(i + 1).trim();
                }
            }
        }
        if (host.equals("market.lobehub.com")) {
            if (nonEmpty.size() >= 5 && nonEmpty.get(0).equals("api") && nonEmpty.get(1).equals("v1")
                    && nonEmpty.get(2).equals("skills") && nonEmpty.get(4).equals("download")) {
                return nonEmpty.get(3).trim();
            }
        }
        return "";
    }

    private String[] extractModelScopeSpec(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost().toLowerCase();
        String[] parts = uri.getPath().split("/");
        List<String> nonEmpty = new ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) nonEmpty.add(p);
        if (nonEmpty.size() < 3 || !nonEmpty.get(0).equals("skills")) return null;
        String owner = nonEmpty.get(1).trim();
        String skillName = nonEmpty.get(2).trim();
        if (owner.isEmpty() || skillName.isEmpty()) return null;
        String versionHint = "";
        if (nonEmpty.size() >= 6 && nonEmpty.get(3).equals("archive") && nonEmpty.get(4).equals("zip")) {
            String archive = nonEmpty.get(5).trim();
            if (archive.endsWith(".zip")) archive = archive.substring(0, archive.length() - 4);
            versionHint = archive;
        }
        return new String[]{owner, skillName, versionHint};
    }

    private String relativeFromRoot(String fullPath, String root) {
        if (root == null || root.isEmpty()) return fullPath.replaceAll("^/+", "");
        String prefix = root.replaceAll("/+$", "") + "/";
        if (fullPath.startsWith(prefix)) return fullPath.substring(prefix.length());
        return fullPath;
    }

    private boolean versionNonEmpty(String version) {
        return version != null && !version.trim().isEmpty();
    }

    // ------------------------------------------------------------------
    // GitHub cache
    // ------------------------------------------------------------------

    private <T> T githubCachedCall(String key, java.util.function.Supplier<T> factory) {
        synchronized (githubCache) {
            CacheEntry entry = githubCache.get(key);
            if (entry != null) {
                if (System.currentTimeMillis() - ((long) entry.timestamp) <= GITHUB_CACHE_TTL_MS) {
                    return (T) entry.value;
                }
                githubCache.remove(key);
            }
        }
        T result = factory.get();
        synchronized (githubCache) {
            githubCache.put(key, new CacheEntry(System.currentTimeMillis(), result));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // HTTP primitives (retry + GitHub token + size limits)
    // ------------------------------------------------------------------

    private Map<String, String> requestHeaders(String accept) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", accept);
        headers.put("User-Agent", "majo-skills-hub/1.0");
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) token = System.getenv("GH_TOKEN");
        if (token != null && !token.isBlank()) headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    private Object httpJsonGet(String url) {
        String body = httpGet(url, "application/json");
        try {
            return mapper.readValue(body, new TypeReference<Object>() {});
        } catch (IOException e) {
            throw new SkillsError("Invalid JSON response from hub URL: " + url);
        }
    }

    private String httpTextGet(String url) {
        return httpGet(url, "text/plain, text/markdown, */*");
    }

    private String httpGet(String url, String accept) {
        byte[] payload = httpBytesGet(url, accept, null);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private byte[] httpBytesGet(String url, String accept, Long maxBytes) {
        String useAccept = accept == null ? "application/octet-stream, */*" : accept;
        Map<String, String> headers = requestHeaders(useAccept);
        int attempts = MAX_RETRIES + 1;
        RuntimeException lastError = null;
        String host = URI.create(url).getHost() == null ? "" : URI.create(url).getHost().toLowerCase();

        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .GET();
            headers.forEach(builder::header);
            try {
                HttpResponse<byte[]> response = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 400) {
                    String bodyText = new String(response.body(), StandardCharsets.UTF_8);
                    SkillsError err = new SkillsError("HTTP " + response.statusCode() + " from " + url
                            + (bodyText.isBlank() ? "" : ": " + extractErrorMessage(bodyText)));
                    if (response.statusCode() == 403 && host.contains("api.github.com")
                            && bodyText.toLowerCase().contains("rate limit")) {
                        throw new SkillsError("GitHub API rate limit exceeded. Set GITHUB_TOKEN to increase the limit, then retry.");
                    }
                    if (RETRYABLE.contains(response.statusCode()) && attempt < attempts) {
                        lastError = err;
                        sleepBackoff(attempt);
                        continue;
                    }
                    throw err;
                }
                long contentLength = -1;
                String cl = response.headers().firstValue("Content-Length").orElse(null);
                if (cl != null) { try { contentLength = Long.parseLong(cl); } catch (NumberFormatException ignored) {} }
                if (maxBytes != null && contentLength > maxBytes) {
                    throw new SkillsError("Response body too large from " + url + ": "
                            + contentLength + " bytes exceeds limit " + maxBytes);
                }
                byte[] body = response.body();
                if (maxBytes != null && body.length > maxBytes) {
                    throw new SkillsError("Response body too large from " + url
                            + ": download exceeded limit " + maxBytes);
                }
                return body;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SkillsError("Interrupted while fetching hub URL: " + url);
            } catch (java.net.http.HttpTimeoutException e) {
                lastError = new SkillsError("Timeout fetching hub URL: " + url);
                if (attempt < attempts) { sleepBackoff(attempt); continue; }
            } catch (IOException e) {
                lastError = new SkillsError("Transport error fetching hub URL: " + url + " (" + e.getMessage() + ")");
                if (attempt < attempts) { sleepBackoff(attempt); continue; }
            } catch (SkillsError e) {
                if (RETRYABLE.contains(guessStatus(e)) && attempt < attempts && !e.getMessage().contains("rate limit")) {
                    lastError = e;
                    sleepBackoff(attempt);
                    continue;
                }
                throw e;
            }
        }
        throw lastError != null ? lastError : new SkillsError("Failed to request hub URL: " + url);
    }

    private int guessStatus(SkillsError e) {
        try {
            String msg = e.getMessage();
            int idx = msg.indexOf("HTTP ");
            if (idx >= 0) {
                String rest = msg.substring(idx + 4).trim();
                int space = rest.indexOf(' ');
                String num = space >= 0 ? rest.substring(0, space) : rest;
                return Integer.parseInt(num.trim());
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void sleepBackoff(int attempt) {
        long delay = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * (1L << Math.max(0, attempt - 1)));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractErrorMessage(String bodyText) {
        try {
            Object data = mapper.readValue(bodyText, new TypeReference<Object>() {});
            if (data instanceof Map<?, ?> m) {
                Object err = m.get("error") != null ? m.get("error") : m.get("message");
                if (err instanceof String s && !s.isBlank()) return s.trim();
            }
            return bodyText.trim();
        } catch (IOException e) {
            return bodyText.trim();
        }
    }

    // ------------------------------------------------------------------
    // Misc helpers
    // ------------------------------------------------------------------

    private String joinUrl(String base, String path) {
        return base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encodePath(String path) {
        String cleaned = path.replaceAll("^/+", "").replaceAll("/+$", "");
        if (cleaned.isEmpty()) return "";
        return urlEncode(cleaned);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() instanceof String k) out.put(k, e.getValue());
        }
        return out;
    }
}
