package com.agent.coding.service;

import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ProviderModelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible model discovery + connection tests
 * providers.py (fetch_provider_models / test_provider_connection). Calls the
 * provider's {@code GET /models} endpoint; no external dependencies.
 */
@Service
public class ModelDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ModelDiscoveryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ProviderModelRepository providerModelRepo;

    public ModelDiscoveryService(ProviderModelRepository providerModelRepo) {
        this.providerModelRepo = providerModelRepo;
    }

    public record DiscoveryResult(List<ProviderModelEntity> models, boolean success, String message) {}

    /**
     * Fetch models from an OpenAI-compatible base URL. When {@code save} is
     * true, upserts them into the provider's model table.
     */
    public DiscoveryResult discover(String providerId, String baseUrl, String apiKey, boolean save) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new DiscoveryResult(List.of(), false, "base_url is required");
        }
        try {
            String modelsUrl = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            HttpRequest req = HttpRequest.newBuilder(URI.create(modelsUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new DiscoveryResult(List.of(), false,
                        "Models request failed with HTTP " + resp.statusCode());
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode data = root.path("data");
            List<ProviderModelEntity> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                if (id.isBlank()) {
                    continue;
                }
                ProviderModelEntity entity = providerModelRepo
                        .findByProviderIdAndModelId(providerId, id)
                        .orElseGet(ProviderModelEntity::new);
                entity.setProviderId(providerId);
                entity.setModelId(id);
                entity.setName(node.path("name").asText(id));
                entity.setProbeSource("openai-models");
                entity.setMaxTokens(8192);
                entity.setMaxInputLength(131072);
                entity.setMaxInputLengthConfigured(false);
                if (save) {
                    providerModelRepo.save(entity);
                }
                models.add(entity);
            }
            return new DiscoveryResult(models, true, "Discovered " + models.size() + " models");
        } catch (Exception e) {
            log.warn("Model discovery failed for {}: {}", providerId, e.getMessage());
            return new DiscoveryResult(List.of(), false, e.getMessage() == null ? "discovery failed" : e.getMessage());
        }
    }

    /** Ping the provider's /models endpoint to verify credentials/connectivity. */
    public boolean testConnection(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            String modelsUrl = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            HttpRequest req = HttpRequest.newBuilder(URI.create(modelsUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.warn("Connection test failed: {}", e.getMessage());
            return false;
        }
    }
}
