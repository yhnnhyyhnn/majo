package com.agent.coding.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Workspace checkpoint system, ported from qwenpaw checkpoints/ (service,
 * repository, policy, models). Snapshots are git commits in the workspace's
 * checkpoint repo referenced by refs/auto|snap|pre-restore, with metadata
 * persisted in .checkpoints/heads.json.
 *
 * A lightweight per-workspace git repo (.checkpoints/git) holds the object
 * database so snapshots never disturb the user's own project repo.
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);
    private static final String HEADS_FILE = ".checkpoints/heads.json";
    private static final String CONFIG_FILE = ".checkpoints/config.json";

    private final ConcurrentHashMap<String, Path> repoLocks = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Repo helpers
    // ------------------------------------------------------------------

    public Path gitDir(Path workspace) {
        return workspace.resolve(".checkpoints").resolve("git");
    }

    private Repository openRepo(Path workspace) throws IOException {
        Path git = gitDir(workspace);
        Files.createDirectories(git.getParent());
        if (!Files.exists(git.resolve("HEAD"))) {
            try (Git g = Git.init()
                    .setDirectory(git.getParent().getParent().toFile())
                    .setGitDir(git.toFile()).call()) {
                // repo created
            } catch (Exception e) {
                throw new IOException("Failed to init checkpoint repo", e);
            }
        }
        return org.eclipse.jgit.storage.file.FileRepositoryBuilder.create(git.toFile());
    }

    private Path workspaceFromRepo(Repository repo) {
        return repo.getWorkTree() == null
                ? repo.getDirectory().getParentFile().getParentFile().toPath()
                : repo.getWorkTree().toPath();
    }

    private static ObjectId resolve(Repository repo, String rev) throws IOException {
        ObjectId id = repo.resolve(rev);
        if (id == null) {
            throw new IllegalArgumentException("Unknown commit: " + rev);
        }
        return id;
    }

    /** Write the full workspace tree as a commit and return its id. */
    public String writeWorkspaceTree(Path workspace) throws IOException {
        try (Repository repo = openRepo(workspace)) {
            RevCommit commit;
            try (Git git = new Git(repo)) {
                git.add().addFilepattern(".").call();
                commit = git.commit()
                        .setMessage("checkpoint")
                        .setAllowEmpty(true)
                        .setAll(true)
                        .call();
            } catch (Exception e) {
                throw new IOException("Checkpoint commit failed: " + e.getMessage(), e);
            }
            return commit == null ? "" : commit.getName();
        }
    }

    private RevCommit headCommit(Repository repo) {
        try {
            ObjectId head = repo.resolve("HEAD");
            if (head == null) {
                return null;
            }
            try (RevWalk walk = new RevWalk(repo)) {
                return walk.parseCommit(head);
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Create a git ref pointing at a commit (refs/auto|snap|pre-restore/...). */
    public boolean refExists(Path workspace, String ref) throws IOException {
        try (Repository repo = openRepo(workspace)) {
            return repo.findRef(ref) != null;
        }
    }

    public void updateRef(Path workspace, String ref, String commit) throws IOException {
        try (Repository repo = openRepo(workspace)) {
            RefUpdate ru = repo.updateRef(ref);
            ru.setNewObjectId(ObjectId.fromString(commit));
            ru.setForceUpdate(true);
            ru.update();
        }
    }

    public void deleteRef(Path workspace, String ref) throws IOException {
        try (Repository repo = openRepo(workspace)) {
            RefUpdate ru = repo.updateRef(ref);
            ru.setForceUpdate(true);
            ru.delete();
        }
    }

    /** List checkpoint refs: name -> commit id. */
    public Map<String, String> listRefs(Path workspace) throws IOException {
        Map<String, String> refs = new LinkedHashMap<>();
        try (Repository repo = openRepo(workspace)) {
            var refsMap = repo.getRefDatabase().getRefsByPrefix(
                    "refs/auto/", "refs/snap/", "refs/pre-restore/");
            for (org.eclipse.jgit.lib.Ref r : refsMap) {
                refs.put(r.getName(), r.getObjectId().getName());
            }
        }
        return refs;
    }

    // ------------------------------------------------------------------
    // Session heads
    // ------------------------------------------------------------------

    public String sessionKey(String channel, String userId, String sessionId) {
        String readable = joinReadable(channel, userId, sessionId);
        String digest = sha256Hex("[" + q(channel) + "," + q(userId) + "," + q(sessionId) + "]");
        return readable + "-" + digest;
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\\\"")) + "\"";
    }

    private static String joinReadable(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) sb.append('-');
                sb.append(p);
            }
        }
        String s = sb.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        while (s.startsWith("-")) s = s.substring(1);
        while (s.endsWith("-")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) s = "session";
        if (s.length() > 24) s = s.substring(0, 24);
        while (s.endsWith("-")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "session" : s;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public Map<String, String> loadHeads(Path workspace) {
        Path file = workspace.resolve(HEADS_FILE);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> loaded = ObjectMapperCompat.read(file, Map.class);
            return loaded != null ? new LinkedHashMap<>(loaded) : new LinkedHashMap<>();
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<>();
    }

    public String sessionHead(Path workspace, String key) {
        return loadHeads(workspace).get(key);
    }

    public void setSessionHead(Path workspace, String key, String commit) throws IOException {
        Map<String, String> heads = loadHeads(workspace);
        heads.put(key, commit);
        saveHeads(workspace, heads);
    }

    private void saveHeads(Path workspace, Map<String, String> heads) throws IOException {
        Path file = workspace.resolve(HEADS_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ObjectMapperCompat.write(heads), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // GC settings
    // ------------------------------------------------------------------

    public Map<String, Object> gcSettings(Path workspace) {
        Path file = workspace.resolve(CONFIG_FILE);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("gc_keep_count", 20);
        defaults.put("gc_keep_days", 30);
        defaults.put("pre_restore_retention_days", 7);
        if (!Files.isRegularFile(file)) {
            return defaults;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> loaded = ObjectMapperCompat.read(file, Map.class);
            if (loaded != null) {
                defaults.putAll(loaded);
            }
        } catch (Exception ignored) {
        }
        return defaults;
    }

    public void saveGcSettings(Path workspace, int keepCount, int keepDays,
                               int preRestoreDays) throws IOException {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("gc_keep_count", keepCount);
        cfg.put("gc_keep_days", keepDays);
        cfg.put("pre_restore_retention_days", preRestoreDays);
        Path file = workspace.resolve(CONFIG_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ObjectMapperCompat.write(cfg), StandardCharsets.UTF_8);
    }

    /** Minimal Jackson wrapper (kept local to avoid leaking mapper). */
    private static final class ObjectMapperCompat {
        static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        static String write(Object o) throws IOException {
            return MAPPER.writeValueAsString(o);
        }

        @SuppressWarnings("unchecked")
        static <T> T read(Path file, Class<T> type) throws IOException {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
        }
    }

    /** Sanitize a ref component: alphanumeric, dash, underscore, dot. */
    public static String sanitizeRefComponent(String s) {
        if (s == null || s.isBlank()) {
            return "snap";
        }
        String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        while (out.startsWith("-")) out = out.substring(1);
        while (out.endsWith("-")) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? "snap" : out;
    }

    /** List refs of a kind, most recent first. */
    public List<Map.Entry<String, String>> refsOfKind(Path workspace, String kind) throws IOException {
        String prefix = "refs/" + kind + "/";
        List<Map.Entry<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, String> e : listRefs(workspace).entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                result.add(e);
            }
        }
        result.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        return result;
    }

    /** Parse a ref into its checkpoint metadata. */
    public Map<String, Object> entryFromRef(Path workspace, String ref, String commit,
                                            Map<String, String> heads) {
        Map<String, Object> m = new LinkedHashMap<>();
        String kind;
        String rest = ref;
        if (rest.startsWith("refs/auto/")) {
            kind = "auto";
            rest = rest.substring("refs/auto/".length());
        } else if (rest.startsWith("refs/snap/")) {
            kind = "snap";
            rest = rest.substring("refs/snap/".length());
        } else if (rest.startsWith("refs/pre-restore/")) {
            kind = "pre-restore";
            rest = rest.substring("refs/pre-restore/".length());
        } else {
            kind = "sha";
        }
        int slash = rest.lastIndexOf('/');
        String label = slash >= 0 ? rest.substring(slash + 1) : rest;
        String key = slash >= 0 ? rest.substring(0, slash) : rest;
        m.put("ref", ref);
        m.put("kind", kind);
        m.put("commit", commit);
        m.put("sha", commit.length() > 12 ? commit.substring(0, 12) : commit);
        m.put("name", label);
        m.put("session_key", key);
        m.put("subject", kind + " " + key + " " + label);
        m.put("query", null);
        m.put("channel", "console");
        m.put("restore_index", null);
        m.put("parent_commit", null);
        m.put("is_head", commit.equals(heads.get(key)));
        m.put("user_id", "");
        m.put("session_id", "");
        m.put("session_title", "");
        try {
            long t = commitTimestamp(workspace, commit);
            m.put("timestamp_ms", t);
        } catch (Exception e) {
            m.put("timestamp_ms", 0L);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Snapshot creation
    // ------------------------------------------------------------------

    /** Create a checkpoint snapshot and return its metadata. */
    public Map<String, Object> makeSnapshot(Path workspace, String kind, String sessionId,
                                            String userId, String channel, String name,
                                            String message) throws IOException {
        if (!Set.of("auto", "snap", "pre-restore").contains(kind)) {
            throw new IllegalArgumentException("Unsupported checkpoint kind: " + kind);
        }
        String key = sessionKey(channel, userId, sessionId);
        String parentCommit = sessionHead(workspace, key);
        long nowMs = System.currentTimeMillis();
        String ref;
        String subject;
        String label;
        if ("auto".equals(kind)) {
            ref = "refs/auto/" + key + "/" + nowMs;
            while (refExists(workspace, ref)) {
                nowMs++;
                ref = "refs/auto/" + key + "/" + nowMs;
            }
            subject = "auto " + key + " " + nowMs;
            label = "";
        } else if ("snap".equals(kind)) {
            label = sanitizeRefComponent(name != null && !name.isBlank()
                    ? name : message != null && !message.isBlank() ? message : "snap-" + nowMs);
            ref = "refs/snap/" + key + "/" + label;
            String base = ref;
            int suffix = 1;
            while (refExists(workspace, ref)) {
                suffix++;
                ref = base + "-" + suffix;
            }
            subject = "snapshot " + key + " " + label;
        } else {
            ref = "refs/pre-restore/" + nowMs + "-" + key;
            while (refExists(workspace, ref)) {
                nowMs++;
                ref = "refs/pre-restore/" + nowMs + "-" + key;
            }
            subject = "pre-restore " + key + " " + nowMs;
            label = "";
        }
        String commit = writeWorkspaceTree(workspace);
        if (commit.isEmpty()) {
            throw new IOException("Checkpoint commit failed");
        }
        updateRef(workspace, ref, commit);
        setSessionHead(workspace, key, commit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ref", ref);
        result.put("kind", kind);
        result.put("commit", commit);
        result.put("tree", commit);
        result.put("parent_commit", parentCommit);
        result.put("timestamp_ms", System.currentTimeMillis());
        result.put("subject", subject);
        result.put("name", label);
        result.put("session_key", key);
        result.put("session_id", sessionId);
        result.put("user_id", userId);
        result.put("channel", channel);
        return result;
    }

    // ------------------------------------------------------------------
    // Graph / status
    // ------------------------------------------------------------------

    public List<Map<String, Object>> graphEntries(Path workspace) throws IOException {
        Map<String, String> heads = loadHeads(workspace);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, String> e : listRefs(workspace).entrySet()) {
            nodes.add(entryFromRef(workspace, e.getKey(), e.getValue(), heads));
        }
        nodes.sort((a, b) -> {
            long ta = (Long) a.getOrDefault("timestamp_ms", 0L);
            long tb = (Long) b.getOrDefault("timestamp_ms", 0L);
            return Long.compare(tb, ta);
        });
        return nodes;
    }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    /** Compute which workspace files differ between a commit and HEAD. */
    public List<String> changedPaths(Path workspace, String commit) throws IOException {
        List<String> paths = new ArrayList<>();
        try (Repository repo = openRepo(workspace)) {
            ObjectId target = resolve(repo, commit);
            ObjectId head = repo.resolve("HEAD");
            try (Git git = new Git(repo); RevWalk walk = new RevWalk(repo)) {
                var oldTree = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
                var newTree = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
                try (var reader = repo.newObjectReader()) {
                    oldTree.reset(reader, walk.parseTree(target));
                    newTree.reset(reader, walk.parseTree(head));
                }
                List<org.eclipse.jgit.diff.DiffEntry> diffs = git.diff()
                        .setOldTree(oldTree).setNewTree(newTree)
                        .call();
                for (org.eclipse.jgit.diff.DiffEntry d : diffs) {
                    paths.add(d.getNewPath());
                }
            } catch (Exception e) {
                throw new IOException("Failed to diff: " + e.getMessage(), e);
            }
        }
        return paths;
    }

    /** Restore workspace files to the state of a commit (checkout). */
    public List<String> restoreFiles(Path workspace, String commit) throws IOException {
        List<String> restored = new ArrayList<>();
        try (Repository repo = openRepo(workspace)) {
            resolve(repo, commit);
            try (Git git = new Git(repo)) {
                git.checkout().setName(commit).setForced(true).call();
            } catch (Exception e) {
                throw new IOException("Checkout failed: " + e.getMessage(), e);
            }
        }
        return changedPaths(workspace, commit);
    }

    // ------------------------------------------------------------------
    // GC / reset
    // ------------------------------------------------------------------

    /** Delete checkpoint refs beyond retention settings; returns deleted refs. */
    public List<String> gc(Path workspace, boolean dryRun, Integer keepCount,
                           Integer keepDays) throws IOException {
        Map<String, Object> settings = gcSettings(workspace);
        int count = keepCount != null ? keepCount
                : ((Number) settings.getOrDefault("gc_keep_count", 20)).intValue();
        int days = keepDays != null ? keepDays
                : ((Number) settings.getOrDefault("gc_keep_days", 30)).intValue();
        long cutoffMs = days > 0
                ? System.currentTimeMillis() - days * 24L * 3600 * 1000 : 0;

        Map<String, String> refs = listRefs(workspace);
        Map<String, String> heads = loadHeads(workspace);
        List<String> sorted = new ArrayList<>(refs.keySet());
        sorted.sort((a, b) -> {
            try {
                long ta = commitTimestamp(workspace, refs.get(a));
                long tb = commitTimestamp(workspace, refs.get(b));
                if (ta != tb) return Long.compare(tb, ta);
            } catch (Exception ignored) {
            }
            return refs.get(b).compareTo(refs.get(a));
        });

        Map<String, Integer> perSession = new LinkedHashMap<>();
        List<String> deleted = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String ref : sorted) {
            String commit = refs.get(ref);
            long ts = 0;
            try {
                ts = commitTimestamp(workspace, commit);
            } catch (Exception ignored) {
            }
            boolean headRef = heads.containsValue(commit);
            boolean tooOld = days > 0 && ts > 0 && ts < cutoffMs;
            String session = refSession(ref);
            int countInSession = perSession.merge(session, 1, Integer::sum);
            boolean tooMany = count > 0 && countInSession > count;
            if (headRef || (!tooOld && !tooMany)) {
                kept.add(ref);
            } else {
                deleted.add(ref);
                if (!dryRun) {
                    deleteRef(workspace, ref);
                }
            }
        }
        return deleted;
    }

    /** Remove all checkpoint refs and heads metadata. */
    public void reset(Path workspace) throws IOException {
        for (String ref : listRefs(workspace).keySet()) {
            deleteRef(workspace, ref);
        }
        Files.deleteIfExists(workspace.resolve(HEADS_FILE));
    }

    private long commitTimestamp(Path workspace, String commit) throws IOException {
        try (Repository repo = openRepo(workspace); RevWalk walk = new RevWalk(repo)) {
            RevCommit c = walk.parseCommit(resolve(repo, commit));
            return c.getCommitTime() * 1000L;
        }
    }

    private static String refSession(String ref) {
        String rest = ref.replaceFirst("^refs/(auto|snap|pre-restore)/", "");
        int slash = rest.indexOf('/');
        return slash > 0 ? rest.substring(0, slash) : rest;
    }
}
