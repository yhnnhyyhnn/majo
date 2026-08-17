package com.agent.coding.service;

import com.agent.coding.agent.AgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Lightweight keyword memory index for an agent workspace. Scans the agent's
 * memory/ directory and top-level markdown files, builds an inverted
 * keyword index, and persists it to memory/.index.json. This replaces the
 * the ReMe vector backend with a zero-dependency file scan so the
 * frontend rebuildMemoryIndex action has real semantics.
 */
@Service
public class MemoryIndexService {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    /** In-memory snapshot of the last built index per agent. */
    private final ConcurrentHashMap<String, Map<String, Object>> lastIndexes = new ConcurrentHashMap<>();

    public Map<String, Object> rebuild(String agentId) {
        return rebuildForPath(AgentStore.workspaceDirForAgent(agentId));
    }

    /** Rebuild the keyword index for an explicit workspace (test-friendly). */
    public Map<String, Object> rebuildForPath(Path workspace) {
        String agentId = workspace.getFileName() == null
                ? "default" : workspace.getFileName().toString();
        long start = System.currentTimeMillis();
        List<Path> files = collectFiles(workspace);

        Map<String, List<Integer>> inverted = new LinkedHashMap<>();
        Map<String, Object> fileMeta = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                String content = readContent(file);
                List<String> words = tokenize(content);
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String w : words) {
                    counts.merge(w, 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    inverted.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
                }
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("path", workspace.relativize(file).toString().replace('\\', '/'));
                meta.put("words", words.size());
                meta.put("bytes", readLength(file));
                fileMeta.put(workspace.relativize(file).toString().replace('\\', '/'), meta);
            } catch (IOException e) {
                log.warn("Skipping unreadable memory file {}: {}", file, e.getMessage());
            }
        }

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("agent_id", agentId);
        index.put("built_at", java.time.Instant.now().toString());
        index.put("files", fileMeta);
        index.put("index", inverted);
        lastIndexes.put(agentId, index);
        persist(workspace, index);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "completed");
        result.put("indexed_files", fileMeta.size());
        result.put("keywords", inverted.size());
        result.put("duration_ms", System.currentTimeMillis() - start);
        return result;
    }

    /** Search the last built index; returns file paths matching every keyword. */
    public List<String> search(String agentId, String query) {
        Map<String, Object> index = lastIndexes.get(agentId);
        if (index == null) {
            Path workspace = AgentStore.workspaceDirForAgent(agentId);
            index = loadPersisted(workspace);
            if (index != null) {
                lastIndexes.put(agentId, index);
            }
        }
        return searchIndex(index, query);
    }

    /** Search an explicitly provided workspace (test-friendly). */
    public List<String> searchForPath(Path workspace, String query) {
        Map<String, Object> index = loadPersisted(workspace);
        return searchIndex(index, query);
    }

    private List<String> searchIndex(Map<String, Object> index, String query) {
        if (index == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, List<Integer>> inverted = (Map<String, List<Integer>>) index.get("index");
        if (inverted == null || inverted.isEmpty()) {
            return List.of();
        }
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        for (String term : terms) {
            List<Integer> hits = inverted.get(term);
            if (hits == null || hits.isEmpty()) {
                return List.of();
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) index.get("files");
        return files == null ? List.of() : new ArrayList<>(files.keySet());
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

    private static List<String> tokenize(String text) {
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
