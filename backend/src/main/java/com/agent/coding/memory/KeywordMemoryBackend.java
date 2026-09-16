package com.agent.coding.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Zero-dependency default memory backend: keyword inverted index over the
 * workspace {@code memory/} directory and top-level markdown files, persisted
 * to {@code memory/.index.json} (ADR-0008). Absorbs the former
 * MemoryIndexService.
 */
@Component
public class KeywordMemoryBackend implements MemoryBackend {

    static final String ID = "keyword";

    private static final Logger log = LoggerFactory.getLogger(KeywordMemoryBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SNIPPET_CHARS = 400;
    private static final int MAX_NOTE_CHARS = 8000;

    // Keyed by workspace path (not agent id): rebuildForPath derives its
    // cache key from the workspace it indexes, so keying by agent id would
    // leave stale entries whenever the two spellings diverge (CI caught
    // exactly that — search served a pre-forget index).
    private final ConcurrentHashMap<String, Map<String, Object>> lastIndexes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Path> workspaces = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(MemoryBackendContext context) {
        workspaces.put(context.agentId(), context.workspace());
    }

    @Override
    public void close() {
        // Stateless file scan — nothing to flush.
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getMemoryPrompt() {
        return "You have a persistent long-term memory directory. When the user shares durable "
                + "preferences, project facts, or decisions worth remembering across sessions, "
                + "save them there (or via /memory write). Call memory_search when a request "
                + "might benefit from previously stored knowledge.";
    }

    @Override
    public List<MemoryHit> search(String query, int maxResults) {
        List<MemoryHit> hits = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return hits;
        }
        for (Map.Entry<String, Path> e : workspaces.entrySet()) {
            Map<String, Object> index = lastIndexFor(e.getKey(), e.getValue());
            List<String> files = searchIndex(index, query);
            for (String relPath : files) {
                hits.add(new MemoryHit(relPath, snippetFor(e.getValue(), relPath, query), 1.0,
                        Map.of("backend", ID)));
                if (hits.size() >= maxResults) {
                    return hits;
                }
            }
        }
        return hits;
    }

    @Override
    public void remember(String content, Map<String, Object> metadata) {
        // Keyword backend persists memories as daily markdown notes under
        // memory/daily/ (ADR-0009); the inverted index is refreshed so the
        // new note is immediately retrievable. metadata.agent_id routes the
        // note to that agent's workspace only (ADR-0014) — without it the
        // write goes to every started workspace (legacy single-agent path).
        String target = metadata == null ? null : String.valueOf(metadata.get("agent_id"));
        for (Map.Entry<String, Path> e : workspaces.entrySet()) {
            if (target != null && !target.isBlank() && !"null".equals(target)
                    && !e.getKey().equals(target)) {
                continue;
            }
            appendDailyNote(e.getValue(), content, metadata);
            rebuildForPath(e.getValue());
        }
    }

    void appendDailyNote(Path workspace, String content, Map<String, Object> metadata) {
        if (content == null || content.isBlank()) {
            return;
        }
        String trimmed = content.strip();
        if (trimmed.length() > MAX_NOTE_CHARS) {
            trimmed = trimmed.substring(0, MAX_NOTE_CHARS) + "\n…[内容过长已截断]";
        }
        String date = java.time.LocalDate.now().toString();
        String time = java.time.LocalTime.now().withNano(0).toString();
        StringBuilder note = new StringBuilder();
        note.append("\n### ").append(time).append("\n");
        if (metadata != null && !metadata.isEmpty()) {
            note.append("<!-- metadata: ");
            try {
                note.append(MAPPER.writeValueAsString(metadata));
            } catch (Exception e) {
                note.append("{}");
            }
            note.append(" -->\n");
        }
        note.append(trimmed).append("\n");
        try {
            Path daily = workspace.resolve("memory").resolve("daily");
            Files.createDirectories(daily);
            Path target = daily.resolve(date + ".md");
            Files.writeString(target, note, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            log.debug("[memory:{}] remembered note -> {}", ID, target);
        } catch (IOException e) {
            log.warn("[memory:{}] failed to write daily note: {}", ID, e.getMessage());
        }
    }

    @Override
    public Map<String, Object> rebuild() {
        Path workspace = workspaces.isEmpty() ? null : workspaces.values().iterator().next();
        if (workspace == null) {
            return Map.of("status", "no_workspace");
        }
        return rebuildForPath(workspace);
    }

    // ── Index build / search (former MemoryIndexService core) ────────

    public Map<String, Object> rebuildForPath(Path workspace) {
        String agentId = workspace.getFileName() == null
                ? "default" : workspace.getFileName().toString();
        long start = System.currentTimeMillis();
        List<Path> files = collectFiles(workspace);

        Map<String, List<String>> inverted = new LinkedHashMap<>();
        Map<String, Object> fileMeta = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                String relPath = workspace.relativize(file).toString().replace('\\', '/');
                String content = readContent(file);
                List<String> words = tokenize(content);
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String w : words) {
                    counts.merge(w, 1, Integer::sum);
                }
                for (String word : counts.keySet()) {
                    List<String> posting = inverted.computeIfAbsent(word, k -> new ArrayList<>());
                    if (!posting.contains(relPath)) {
                        posting.add(relPath);
                    }
                }
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("path", relPath);
                meta.put("words", words.size());
                meta.put("bytes", readLength(file));
                fileMeta.put(relPath, meta);
            } catch (IOException e) {
                log.warn("Skipping unreadable memory file {}: {}", file, e.getMessage());
            }
        }

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("agent_id", agentId);
        index.put("built_at", java.time.Instant.now().toString());
        index.put("files", fileMeta);
        index.put("index", inverted);
        lastIndexes.put(cacheKey(workspace), index);
        persist(workspace, index);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("indexed_files", fileMeta.size());
        result.put("keywords", inverted.size());
        result.put("duration_ms", System.currentTimeMillis() - start);
        return result;
    }

    private static String cacheKey(Path workspace) {
        return workspace.toAbsolutePath().normalize().toString();
    }

    private Map<String, Object> lastIndexFor(String agentId, Path workspace) {
        Map<String, Object> index = lastIndexes.get(cacheKey(workspace));
        if (index == null) {
            index = loadPersisted(workspace);
            if (index != null) {
                lastIndexes.put(cacheKey(workspace), index);
            }
        }
        return index;
    }

    private List<String> searchIndex(Map<String, Object> index, String query) {
        if (index == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, List<String>> inverted = (Map<String, List<String>>) index.get("index");
        if (inverted == null || inverted.isEmpty()) {
            return List.of();
        }
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        // Files containing every query term (AND), ordered by index order.
        List<String> result = null;
        for (String term : terms) {
            List<String> postings = inverted.get(term);
            if (postings == null || postings.isEmpty()) {
                return List.of();
            }
            if (result == null) {
                result = new ArrayList<>(postings);
            } else {
                result.retainAll(postings);
            }
            if (result.isEmpty()) {
                return List.of();
            }
        }
        return result == null ? List.of() : result;
    }

    private String snippetFor(Path workspace, String relPath, String query) {
        try {
            Path file = workspace.resolve(relPath);
            String content = readContent(file);
            int at = content.toLowerCase().indexOf(
                    query.trim().toLowerCase().split("\\s+")[0]);
            int from = Math.max(0, at < 0 ? 0 : at - 80);
            String snippet = content.substring(from, Math.min(content.length(), from + MAX_SNIPPET_CHARS));
            return snippet.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Path> collectFiles(Path workspace) {
        List<Path> files = new ArrayList<>();
        Path memoryDir = workspace.resolve("memory");
        if (Files.isDirectory(memoryDir)) {
            collectRecursive(memoryDir, files, 4);
        }
        try (var stream = Files.list(workspace)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(files::add);
        } catch (IOException ignored) {
        }
        return files;
    }

    private static void collectRecursive(Path dir, List<Path> out, int depthLeft) {
        if (depthLeft <= 0) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                if (Files.isDirectory(p)) {
                    collectRecursive(p, out, depthLeft - 1);
                } else if (p.getFileName().toString().toLowerCase().endsWith(".md")
                        && !p.getFileName().toString().startsWith(".")) {
                    out.add(p);
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String readContent(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            return "";
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static long readLength(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (String w : WORD_SPLIT.split(text.toLowerCase())) {
            if (w.length() >= 2 && w.length() <= 64) {
                words.add(w);
            }
        }
        return words;
    }

    private void persist(Path workspace, Map<String, Object> index) {
        try {
            Path target = workspace.resolve("memory").resolve(".index.json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, MAPPER.writeValueAsString(index), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to persist memory index: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadPersisted(Path workspace) {
        try {
            Path target = workspace.resolve("memory").resolve(".index.json");
            if (!Files.isRegularFile(target)) {
                return null;
            }
            return MAPPER.readValue(Files.readString(target, StandardCharsets.UTF_8), Map.class);
        } catch (IOException e) {
            return null;
        }
    }
}
