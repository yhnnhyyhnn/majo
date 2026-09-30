package com.agent.coding.memory;

import com.agent.coding.SettingsService;
import com.agent.coding.service.ModelRoutingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Semantic memory backend (ADR-0015), the majo counterpart of QwenPaw's ReMe
 * embedding recall: memory files are chunked and embedded via an
 * OpenAI-compatible {@code /embeddings} endpoint, and recall ranks chunks by
 * cosine similarity instead of keyword overlap.
 *
 * <p>Extends {@link KeywordMemoryBackend} deliberately: same memory/
 * directory layout and daily-note writing, and the keyword inverted index
 * stays as a built-in fallback — when the embedding endpoint is not
 * configured or fails, search degrades to lexical matching instead of losing
 * recall (same philosophy as {@link SummaryMemoryBackend}: an unavailable
 * model degrades quality, never loses memory).
 *
 * <p>Configuration lives in the running config's
 * {@code reme_light_memory_config.embedding}:
 * {@code {model, base_url?, api_key?, chunk_chars?}}. Unset fields fall back
 * to the agent's effective chat provider connection, then to the global
 * settings row. Select with {@code memory_manager_backend="embedding"}.
 */
@Component
public class EmbeddingMemoryBackend extends KeywordMemoryBackend {

    static final String ID = "embedding";
    static final String INDEX_FILE = ".index-embedding.json";

    private static final Logger log = LoggerFactory.getLogger(EmbeddingMemoryBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_CHUNK_CHARS = 700;
    private static final int MAX_CHUNK_CHARS = 1200;
    private static final double SIMILARITY_FLOOR = 0.15;

    private final ModelRoutingService modelRouting;
    private final SettingsService settingsService;
    private final EmbeddingClient client;

    /** Registered per agent at start; parent's map stays private. */
    private final Map<String, Path> workspaces = new LinkedHashMap<>();
    private final Map<String, EmbeddingClient.Endpoint> endpoints = new LinkedHashMap<>();

    public EmbeddingMemoryBackend(ModelRoutingService modelRouting,
                                  SettingsService settingsService,
                                  EmbeddingClient client) {
        this.modelRouting = modelRouting;
        this.settingsService = settingsService;
        this.client = client;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(MemoryBackendContext context) {
        super.start(context);
        workspaces.put(context.agentId(), context.workspace());
        endpoints.put(context.agentId(), resolveEndpoint(context));
    }

    // ── Recall ───────────────────────────────────────────────────────

    @Override
    public List<MemoryHit> search(String query, int maxResults) {
        if (query == null || query.isBlank() || workspaces.isEmpty()) {
            return List.of();
        }
        // Group workspaces by endpoint: the query must be embedded with the
        // SAME model as the indexes it is compared against (vector dimension
        // and semantic space are model-specific).
        record Target(Path workspace, EmbeddingClient.Endpoint endpoint) {}
        Map<EmbeddingClient.Endpoint, List<Target>> byEndpoint = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : workspaces.entrySet()) {
            EmbeddingClient.Endpoint endpoint = endpoints.get(e.getKey());
            if (endpoint != null) {
                byEndpoint.computeIfAbsent(endpoint, k -> new ArrayList<>())
                        .add(new Target(e.getValue(), endpoint));
            }
        }
        if (byEndpoint.isEmpty()) {
            return super.search(query, maxResults);
        }
        record Scored(String source, String text, double score) {}
        List<Scored> scored = new ArrayList<>();
        boolean semanticRan = false;
        for (Map.Entry<EmbeddingClient.Endpoint, List<Target>> group
                : byEndpoint.entrySet()) {
            float[] q;
            try {
                q = client.embed(group.getKey(), List.of(query.strip())).get(0);
            } catch (Exception e) {
                log.warn("[memory:{}] query embedding failed for model {}: {}",
                        ID, group.getKey().model(), e.getMessage());
                continue;
            }
            for (Target target : group.getValue()) {
                try {
                    Map<String, Object> index =
                            loadOrBuildIndex(target.workspace(), group.getKey());
                    List<float[]> vectors = readVectors(index);
                    List<String> texts = readTexts(index);
                    List<String> sources = readSources(index);
                    semanticRan = semanticRan || !vectors.isEmpty();
                    for (int i = 0; i < vectors.size(); i++) {
                        double sim = cosine(q, vectors.get(i));
                        if (sim >= SIMILARITY_FLOOR) {
                            scored.add(new Scored(sources.get(i), texts.get(i), sim));
                        }
                    }
                } catch (Exception ex) {
                    log.warn("[memory:{}] semantic search for workspace {} failed: {}",
                            ID, target.workspace(), ex.getMessage());
                }
            }
        }
        if (!semanticRan) {
            return super.search(query, maxResults);
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<MemoryHit> hits = new ArrayList<>();
        for (Scored s : scored) {
            if (hits.size() >= maxResults) {
                break;
            }
            hits.add(new MemoryHit(s.source(), snippet(s.text()), s.score(),
                    Map.of("backend", ID)));
        }
        return hits;
    }

    // ── Write ────────────────────────────────────────────────────────

    @Override
    public void remember(String content, Map<String, Object> metadata) {
        // Parent appends the daily note and refreshes the keyword index;
        // afterwards the touched file is re-embedded so recall stays semantic.
        super.remember(content, metadata);
        String agentId = metadata == null || metadata.get("agent_id") == null
                ? "default" : String.valueOf(metadata.get("agent_id"));
        Path workspace = workspaces.get(agentId);
        EmbeddingClient.Endpoint endpoint = endpoints.get(agentId);
        if (workspace == null || endpoint == null) {
            return; // keyword index already updated — lexical recall intact
        }
        try {
            Path daily = workspace.resolve("memory").resolve("daily")
                    .resolve(java.time.LocalDate.now() + ".md");
            if (Files.isRegularFile(daily)) {
                reembedFile(workspace, endpoint, daily);
            }
        } catch (Exception e) {
            log.warn("[memory:{}] failed to embed new memory: {}", ID, e.getMessage());
        }
    }

    @Override
    public Map<String, Object> rebuildForPath(Path workspace) {
        // Parent rebuilds the keyword index; the semantic index is refreshed
        // here so /memory forget also drops stale vectors.
        Map<String, Object> report = super.rebuildForPath(workspace);
        EmbeddingClient.Endpoint endpoint = endpointForWorkspace(workspace);
        if (endpoint != null) {
            try {
                persist(workspace, buildIndex(workspace, endpoint));
            } catch (Exception e) {
                log.warn("[memory:{}] semantic rebuild failed: {}", ID, e.getMessage());
            }
        }
        return report;
    }

    /** Endpoint for a workspace, matched by registered path (not name). */
    private EmbeddingClient.Endpoint endpointForWorkspace(Path workspace) {
        for (Map.Entry<String, Path> e : workspaces.entrySet()) {
            if (e.getValue().equals(workspace) && endpoints.containsKey(e.getKey())) {
                return endpoints.get(e.getKey());
            }
        }
        return null;
    }

    // ── Index build / persistence ────────────────────────────────────

    private Path indexFile(Path workspace) {
        return workspace.resolve("memory").resolve(INDEX_FILE);
    }

    private synchronized Map<String, Object> loadOrBuildIndex(
            Path workspace, EmbeddingClient.Endpoint endpoint) throws Exception {
        Path file = indexFile(workspace);
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> index = MAPPER.readValue(
                        Files.readString(file, StandardCharsets.UTF_8), Map.class);
                if (endpoint.model().equals(index.get("model"))) {
                    return index;
                }
                log.info("[memory:{}] index model {} != {} — rebuilding",
                        ID, index.get("model"), endpoint.model());
            } catch (IOException e) {
                log.warn("[memory:{}] index unreadable — rebuilding: {}", ID, e.getMessage());
            }
        }
        Map<String, Object> index = buildIndex(workspace, endpoint);
        persist(workspace, index);
        return index;
    }

    private Map<String, Object> buildIndex(
            Path workspace, EmbeddingClient.Endpoint endpoint) throws Exception {
        List<Path> files = collectFiles(workspace);
        List<String> texts = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (Path file : files) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String rel = workspace.relativize(file).toString().replace('\\', '/');
                for (String chunk : chunk(content)) {
                    texts.add(chunk);
                    sources.add(rel);
                }
            } catch (IOException e) {
                log.warn("[memory:{}] skipping unreadable {}: {}", ID, file, e.getMessage());
            }
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("model", endpoint.model());
        index.put("built_at", java.time.Instant.now().toString());
        if (texts.isEmpty()) {
            index.put("chunks", List.of());
            return index;
        }
        List<float[]> vectors = client.embed(endpoint, texts);
        List<Map<String, Object>> chunks = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("source", sources.get(i));
            chunk.put("text", texts.get(i));
            chunk.put("vector", toList(vectors.get(i)));
            chunks.add(chunk);
        }
        index.put("chunks", chunks);
        return index;
    }

    /** Re-embed one file: drop its old chunks, append fresh ones. */
    private synchronized void reembedFile(
            Path workspace, EmbeddingClient.Endpoint endpoint, Path file) throws Exception {
        Map<String, Object> index = loadOrBuildIndex(workspace, endpoint);
        String rel = workspace.relativize(file).toString().replace('\\', '/');
        List<Map<String, Object>> chunks = readChunks(index);
        chunks.removeIf(c -> rel.equals(String.valueOf(c.get("source"))));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<String> texts = chunk(content);
        if (!texts.isEmpty()) {
            List<float[]> vectors = client.embed(endpoint, texts);
            for (int i = 0; i < texts.size(); i++) {
                Map<String, Object> chunk = new LinkedHashMap<>();
                chunk.put("source", rel);
                chunk.put("text", texts.get(i));
                chunk.put("vector", toList(vectors.get(i)));
                chunks.add(chunk);
            }
        }
        index.put("chunks", chunks);
        index.put("built_at", java.time.Instant.now().toString());
        persist(workspace, index);
    }

    private void persist(Path workspace, Map<String, Object> index) {
        try {
            Path file = indexFile(workspace);
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(index),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("[memory:{}] failed to persist index: {}", ID, e.getMessage());
        }
    }

    // ── Chunking ─────────────────────────────────────────────────────

    /**
     * Merge paragraphs into chunks of ~{@link #DEFAULT_CHUNK_CHARS}; a single
     * oversized paragraph is hard-split at the cap so no vector has to encode
     * an unbounded text.
     */
    static List<String> chunk(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : content.strip().split("\\n\\s*\\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) {
                continue;
            }
            while (p.length() > MAX_CHUNK_CHARS) {
                if (current.length() > 0) {
                    chunks.add(current.toString().strip());
                    current.setLength(0);
                }
                chunks.add(p.substring(0, MAX_CHUNK_CHARS));
                p = p.substring(MAX_CHUNK_CHARS);
            }
            if (current.length() + p.length() + 2 > DEFAULT_CHUNK_CHARS
                    && current.length() > 0) {
                chunks.add(current.toString().strip());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(p);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().strip());
        }
        return chunks;
    }

    // ── Endpoint resolution ──────────────────────────────────────────

    /**
     * Per-agent endpoint: the agent's reme config {@code embedding} section
     * first ({@code model, base_url, api_key}); unset fields fall back to the
     * agent's effective chat provider connection, then to the global settings
     * row. Null when no embedding model is resolvable — the backend then
     * degrades to the inherited keyword search.
     */
    private EmbeddingClient.Endpoint resolveEndpoint(MemoryBackendContext context) {
        String agentId = context.agentId();
        Map<String, Object> config = context.backendConfig();
        String model = null;
        String baseUrl = null;
        String apiKey = null;
        if (config != null && config.get("embedding") instanceof Map<?, ?> e) {
            model = str(e.get("model"));
            baseUrl = str(e.get("base_url"));
            apiKey = str(e.get("api_key"));
        }
        if (model == null) {
            model = System.getenv("MAJO_EMBEDDING_MODEL");
        }
        if (model != null && (baseUrl == null || apiKey == null)) {
            var slot = safeSlot(agentId);
            if (slot != null && slot.hasBoth()) {
                var conn = modelRouting.resolveProviderConnection(slot.providerId());
                if (conn != null) {
                    if (baseUrl == null) baseUrl = conn.baseUrl();
                    if (apiKey == null) apiKey = conn.apiKey();
                }
            }
        }
        if (baseUrl == null || apiKey == null) {
            try {
                if (baseUrl == null) baseUrl = settingsService.getBaseUrl();
                if (apiKey == null) apiKey = settingsService.getApiKey();
            } catch (Exception ignored) {
            }
        }
        EmbeddingClient.Endpoint endpoint =
                new EmbeddingClient.Endpoint(baseUrl, apiKey, model);
        return endpoint.isComplete() ? endpoint : null;
    }

    private ModelRoutingService.ModelSlot safeSlot(String agentId) {
        try {
            return modelRouting.resolveEffectiveModel(agentId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ── Index accessors ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readChunks(Map<String, Object> index) {
        Object chunks = index.get("chunks");
        return chunks instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();
    }

    private static List<float[]> readVectors(Map<String, Object> index) {
        List<float[]> out = new ArrayList<>();
        for (Map<String, Object> c : readChunks(index)) {
            out.add(toVector(c.get("vector")));
        }
        return out;
    }

    private static List<String> readTexts(Map<String, Object> index) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : readChunks(index)) {
            out.add(String.valueOf(c.get("text")));
        }
        return out;
    }

    private static List<String> readSources(Map<String, Object> index) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : readChunks(index)) {
            out.add(String.valueOf(c.get("source")));
        }
        return out;
    }

    private static List<Float> toList(float[] vector) {
        List<Float> out = new ArrayList<>(vector.length);
        for (float v : vector) {
            out.add(v);
        }
        return out;
    }

    private static float[] toVector(Object o) {
        if (o instanceof List<?> list) {
            float[] v = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                v[i] = ((Number) list.get(i)).floatValue();
            }
            return v;
        }
        return new float[0];
    }

    static double cosine(float[] a, float[] b) {
        if (a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String snippet(String text) {
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
