package com.agent.coding.service;

import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ProviderModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Model pricing lookup and cost calculation (ADR-0017), following the
 * conservative classification of qwenpaw #7899's model_billing: a price
 * exists only when explicitly stored; free means both rates are explicitly
 * zero; missing values are never manufactured. Cost is USD via the
 * USD-per-1M-tokens convention.
 */
@Service
public class ModelPricingService {

    private static final Logger log = LoggerFactory.getLogger(ModelPricingService.class);

    /** Complete price pair; either field null means "unknown". */
    public record Prices(Double inputPerMillion, Double outputPerMillion) {
        public boolean known() {
            return inputPerMillion != null && outputPerMillion != null;
        }
    }

    private final ProviderModelRepository providerModelRepo;

    public ModelPricingService(ProviderModelRepository providerModelRepo) {
        this.providerModelRepo = providerModelRepo;
    }

    /** Stored price pair for the model, or null when no row exists. */
    public Prices pricesFor(String providerId, String modelId) {
        if (providerId == null || modelId == null) {
            return null;
        }
        try {
            return providerModelRepo
                    .findByProviderIdAndModelId(providerId, modelId)
                    .map(m -> new Prices(m.getInputPrice(), m.getOutputPrice()))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[pricing] lookup failed for {}/{}: {}", providerId, modelId, e.getMessage());
            return null;
        }
    }

    /**
     * Cost of one turn in USD, or null when the price pair is incomplete —
     * a half-known price never yields a half-truthful cost.
     */
    public Double costFor(String providerId, String modelId, long inputTokens, long outputTokens) {
        Prices prices = pricesFor(providerId, modelId);
        if (prices == null || !prices.known()) {
            return null;
        }
        return inputTokens / 1_000_000d * prices.inputPerMillion()
                + outputTokens / 1_000_000d * prices.outputPerMillion();
    }
}
