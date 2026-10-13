package com.agent.coding.service;

import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.repository.ProviderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog sync semantics (ADR-0018): capability/pricing/thinking metadata
 * refreshes from the vendored catalog while user-owned values (configured
 * context length, thinking choices, hand-edited max_tokens) and unmatched
 * providers stay untouched. Uses a fixture catalog via
 * majo.catalog.location so the real 31-file snapshot never runs in tests.
 */
@SpringBootTest(properties = "majo.catalog.location=classpath:catalog-test/providers")
class CatalogSyncServiceTest {

    private static final String PID = "catalog-sync-test";

    @Autowired
    private CatalogSyncService service;

    @Autowired
    private ProviderRepository providerRepo;

    @Autowired
    private ProviderModelRepository modelRepo;

    private ProviderEntity provider() {
        return providerRepo.findById(PID).orElseThrow();
    }

    private ProviderModelEntity row(String modelId) {
        return modelRepo.findByProviderIdAndModelId(PID, modelId).orElseThrow();
    }

    @Test
    @Transactional
    void syncUpdatesMetadataAndProtectsUserValues() {
        ProviderEntity p = new ProviderEntity();
        p.setId(PID);
        p.setName("Catalog Sync Test");
        p.setBaseUrl("https://catalog-test.example/v1");
        p.setApiKey("sk-test");
        providerRepo.save(p);

        ProviderModelEntity existing = new ProviderModelEntity();
        existing.setProviderId(PID);
        existing.setModelId("existing-model");
        existing.setName("Old Name");
        existing.setMaxInputLength(8192);
        modelRepo.save(existing);

        ProviderModelEntity configured = new ProviderModelEntity();
        configured.setProviderId(PID);
        configured.setModelId("configured-model");
        configured.setName("Configured");
        configured.setMaxInputLength(4096);
        configured.setMaxInputLengthConfigured(true);
        modelRepo.save(configured);

        ProviderModelEntity priceKeep = new ProviderModelEntity();
        priceKeep.setProviderId(PID);
        priceKeep.setModelId("price-keep-model");
        priceKeep.setName("Price Keep");
        priceKeep.setInputPrice(1.25);
        priceKeep.setOutputPrice(9.75);
        modelRepo.save(priceKeep);

        ProviderModelEntity userThinking = new ProviderModelEntity();
        userThinking.setProviderId(PID);
        userThinking.setModelId("user-thinking-model");
        userThinking.setName("User Thinking");
        userThinking.setThinkingEnabled(true);
        userThinking.setThinkingBudget(4096);
        userThinking.setThinkingParamStyle("budget");
        modelRepo.save(userThinking);

        ProviderModelEntity maxTokensRow = new ProviderModelEntity();
        maxTokensRow.setProviderId(PID);
        maxTokensRow.setModelId("maxtokens-protected-model");
        maxTokensRow.setName("MaxTokens");
        maxTokensRow.setMaxTokens(1000);
        modelRepo.save(maxTokensRow);

        var result = service.sync();

        // Only the fixture provider matched; ghost provider file skipped.
        assertEquals(1, result.providersMatched());
        assertEquals(5, result.modelsUpdated());
        assertEquals(1, result.modelsAdded());
        assertTrue(modelRepo.findByProviderIdAndModelId("no-such-provider", "ghost-model").isEmpty());

        // Existing row: capabilities, context, prices, thinking metadata.
        ProviderModelEntity e = row("existing-model");
        assertTrue(e.getSupportsImage());
        assertFalse(e.getSupportsVideo());
        assertTrue(e.getSupportsMultimodal());
        assertEquals(262144, e.getMaxInputLength());
        assertEquals(3.0, e.getInputPrice(), 1e-9);
        assertEquals(15.0, e.getOutputPrice(), 1e-9);
        assertEquals("effort", e.getThinkingParamStyle());
        assertTrue(String.valueOf(e.getReasoningEffortOptions()).contains("\"low\""));
        assertNull(e.getThinkingEnabled(), "user thinking value untouched");

        // Configured context length is protected.
        assertEquals(4096, row("configured-model").getMaxInputLength());

        // Catalog entry without pricing keeps the stored prices.
        assertEquals(1.25, row("price-keep-model").getInputPrice(), 1e-9);
        assertEquals(9.75, row("price-keep-model").getOutputPrice(), 1e-9);

        // Thinking metadata refreshed, user choices intact.
        ProviderModelEntity ut = row("user-thinking-model");
        assertEquals(Boolean.TRUE, ut.getThinkingEnabled());
        assertEquals(4096, ut.getThinkingBudget());
        assertEquals("[1024,65536]", ut.getThinkingBudgetRange());

        // New row inserted with catalog values.
        ProviderModelEntity fresh = row("brand-new-model");
        assertEquals(1048576, fresh.getMaxInputLength());
        assertEquals(65536, fresh.getMaxTokens());
        assertEquals(Boolean.TRUE, fresh.getIsFree());
        assertEquals(0.0, fresh.getInputPrice(), 1e-9);
        assertEquals(0.0, fresh.getOutputPrice(), 1e-9);

        // Hand-edited max_tokens on existing rows is not overwritten.
        assertEquals(1000, row("maxtokens-protected-model").getMaxTokens());

        // Never deletes: nothing removed.
        Optional<ProviderModelEntity> stillThere = modelRepo.findByProviderIdAndModelId(PID, "existing-model");
        assertTrue(stillThere.isPresent());
    }
}
