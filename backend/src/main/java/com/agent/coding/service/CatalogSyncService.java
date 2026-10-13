package com.agent.coding.service;

import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.repository.ProviderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * models.dev catalog sync (ADR-0018): the vendored per-provider snapshot
 * (config/catalog/providers/*.json, upstream #7899 data) refreshes model
 * metadata in place. Conservative by construction: providers match by exact
 * file-name id, nothing is ever deleted, user-owned values (configured
 * context length, thinking on/off/budget/effort choices, hand-edited
 * max_tokens on existing rows) are never touched, and a missing catalog
 * price never erases a stored one.
 */
@Service
public class CatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncService.class);

    public record SyncResult(int providersMatched, int modelsUpdated, int modelsAdded) {
    }

    private final ProviderRepository providerRepo;
    private final ProviderModelRepository providerModelRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Value("${majo.catalog.location:classpath*:config/catalog/providers}")
    private String catalogLocation;

    public CatalogSyncService(ProviderRepository providerRepo,
                              ProviderModelRepository providerModelRepo) {
        this.providerRepo = providerRepo;
        this.providerModelRepo = providerModelRepo;
    }

    /** Sync every catalog file whose name matches an existing provider id. */
    @Transactional
    public SyncResult sync() {
        int matched = 0, updated = 0, added = 0;
        try {
            Resource[] resources = resolver.getResources(catalogLocation + "/*.json");
            for (Resource resource : resources) {
                String providerId = fileNameOf(resource);
                if (providerId == null || providerRepo.findById(providerId).isEmpty()) {
                    continue;
                }
                matched++;
                JsonNode root = mapper.readTree(resource.getInputStream());
                for (JsonNode entry : root.path("models")) {
                    String modelId = entry.path("id").asText("");
                    if (modelId.isBlank()) {
                        continue;
                    }
                    Optional<ProviderModelEntity> existing =
                            providerModelRepo.findByProviderIdAndModelId(providerId, modelId);
                    if (existing.isPresent()) {
                        applyToExisting(existing.get(), entry);
                        providerModelRepo.save(existing.get());
                        updated++;
                    } else {
                        providerModelRepo.save(newFromCatalog(providerId, modelId, entry));
                        added++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[catalog] sync failed partway: {}", e.getMessage());
        }
        log.info("[catalog] sync done: providers={}, updated={}, added={}",
                matched, updated, added);
        return new SyncResult(matched, updated, added);
    }

    /** Mutate an existing row from a catalog entry (capability metadata is catalog-owned). */
    private void applyToExisting(ProviderModelEntity row, JsonNode entry) {
        boolean image = entry.path("supports_image").asBoolean(false);
        boolean video = entry.path("supports_video").asBoolean(false);
        row.setSupportsImage(image);
        row.setSupportsVideo(video);
        row.setSupportsMultimodal(image || video);

        String probeSource = entry.path("probe_source").asText(null);
        if (probeSource != null && !probeSource.isBlank()) {
            row.setProbeSource(probeSource);
        }
        row.setIsFree(entry.path("is_free").asBoolean(false));

        if (!Boolean.TRUE.equals(row.getMaxInputLengthConfigured())) {
            int maxInput = entry.path("max_input_length").asInt(0);
            if (maxInput > 0) {
                row.setMaxInputLength(maxInput);
            }
        }

        // Prices: catalog quote wins when present; absence keeps the stored
        // value (never manufacture, never erase).
        JsonNode pricing = entry.path("pricing");
        if (pricing.isObject()) {
            Double in = ModelPricingService.perMillion(pricing.path("prompt").asText(null));
            Double out = ModelPricingService.perMillion(pricing.path("completion").asText(null));
            if (in != null && out != null) {
                row.setInputPrice(in);
                row.setOutputPrice(out);
            }
        }

        applyThinkingMetadata(row, entry);
    }

    private ProviderModelEntity newFromCatalog(String providerId, String modelId, JsonNode entry) {
        ProviderModelEntity row = new ProviderModelEntity();
        row.setProviderId(providerId);
        row.setModelId(modelId);
        row.setName(entry.path("name").asText(modelId));
        row.setSupportsImage(entry.path("supports_image").asBoolean(false));
        row.setSupportsVideo(entry.path("supports_video").asBoolean(false));
        row.setSupportsMultimodal(row.getSupportsImage() || row.getSupportsVideo());
        String probeSource = entry.path("probe_source").asText(null);
        row.setProbeSource(probeSource == null || probeSource.isBlank() ? "catalog" : probeSource);
        row.setIsFree(entry.path("is_free").asBoolean(false));
        int maxOutput = entry.path("max_output_length").asInt(0);
        row.setMaxTokens(maxOutput > 0 ? maxOutput : 8192);
        int maxInput = entry.path("max_input_length").asInt(0);
        row.setMaxInputLength(maxInput > 0 ? maxInput : 131072);

        JsonNode pricing = entry.path("pricing");
        if (pricing.isObject()) {
            Double in = ModelPricingService.perMillion(pricing.path("prompt").asText(null));
            Double out = ModelPricingService.perMillion(pricing.path("completion").asText(null));
            if (in != null && out != null) {
                row.setInputPrice(in);
                row.setOutputPrice(out);
            }
        }

        applyThinkingMetadata(row, entry);
        return row;
    }

    /** Thinking metadata (UI shape) refreshes; user values stay put. */
    private static void applyThinkingMetadata(ProviderModelEntity row, JsonNode entry) {
        JsonNode tc = entry.path("thinking_control");
        if (!tc.isObject() || !tc.hasNonNull("kind")) {
            return;
        }
        String kind = tc.path("kind").asText();
        if (!"effort".equals(kind) && !"budget".equals(kind)) {
            return;
        }
        row.setThinkingParamStyle(kind);
        if (tc.path("efforts").isArray()) {
            row.setReasoningEffortOptions(tc.path("efforts").toString());
        }
        JsonNode min = tc.path("budget_min");
        JsonNode max = tc.path("budget_max");
        if (min.isNumber() && max.isNumber()) {
            row.setThinkingBudgetRange("[" + min.asInt() + "," + max.asInt() + "]");
        }
    }

    private static String fileNameOf(Resource resource) {
        try {
            String name = resource.getFilename();
            return name != null && name.endsWith(".json") ? name.substring(0, name.length() - 5) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
