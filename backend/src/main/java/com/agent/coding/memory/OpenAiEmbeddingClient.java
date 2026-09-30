package com.agent.coding.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible {@code POST /embeddings} client (ADR-0015). Works with
 * OpenAI, Azure-style gateways, and self-hosted servers (vLLM, Ollama's
 * OpenAI layer, DashScope-compatible endpoints) that majo's chat providers
 * already target. Input texts are batched in one request; the response's
 * {@code data[i].embedding} arrays map back to the inputs in order.
 */
@Component
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Batch cap: keeps request bodies comfortably under server limits. */
    private static final int BATCH_SIZE = 32;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public List<float[]> embed(Endpoint endpoint, List<String> inputs) throws Exception {
        List<float[]> out = new ArrayList<>(inputs.size());
        for (int from = 0; from < inputs.size(); from += BATCH_SIZE) {
            List<String> batch = inputs.subList(from, Math.min(inputs.size(), from + BATCH_SIZE));
            out.addAll(embedBatch(endpoint, batch));
        }
        return out;
    }

    private List<float[]> embedBatch(Endpoint endpoint, List<String> inputs) throws Exception {
        String base = (endpoint.baseUrl() == null || endpoint.baseUrl().isBlank())
                ? "https://api.openai.com/v1" : endpoint.baseUrl().strip();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.endsWith("/v1") && !base.contains("/v1/")) {
            base = base + "/v1";
        }
        var body = MAPPER.createObjectNode();
        body.put("model", endpoint.model());
        var array = body.putArray("input");
        for (String input : inputs) {
            array.add(input);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/embeddings"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + endpoint.apiKey());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("[memory:embedding] endpoint returned {}: {}",
                    response.statusCode(), snippet(response.body()));
            throw new IllegalStateException("embedding endpoint returned " + response.statusCode());
        }
        JsonNode data = MAPPER.readTree(response.body()).path("data");
        if (!data.isArray() || data.size() != inputs.size()) {
            throw new IllegalStateException("embedding response size mismatch: expected "
                    + inputs.size() + ", got " + data.size());
        }
        List<float[]> vectors = new ArrayList<>(inputs.size());
        for (JsonNode item : data) {
            JsonNode vector = item.path("embedding");
            if (!vector.isArray() || vector.isEmpty()) {
                throw new IllegalStateException("embedding response missing vector");
            }
            float[] v = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                v[i] = vector.get(i).floatValue();
            }
            vectors.add(v);
        }
        return vectors;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }
}
