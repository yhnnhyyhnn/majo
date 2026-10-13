package com.agent.coding.service;

import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ProviderModelRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Conservative pricing semantics (ADR-0017, qwenpaw #7899 model_billing):
 * a cost exists only from a complete explicit price pair; missing values
 * are never manufactured.
 */
class ModelPricingServiceTest {

    private final ProviderModelRepository repo = mock(ProviderModelRepository.class);
    private final ModelPricingService service = new ModelPricingService(repo);

    private void stub(Double in, Double out) {
        ProviderModelEntity m = new ProviderModelEntity();
        m.setProviderId("p");
        m.setModelId("m");
        m.setInputPrice(in);
        m.setOutputPrice(out);
        when(repo.findByProviderIdAndModelId(anyString(), anyString()))
                .thenReturn(Optional.of(m));
    }

    @Test
    void costArithmeticIsUsdPerMillion() {
        stub(3.0, 15.0);
        assertEquals(6.0, service.costFor("p", "m", 1_000_000, 200_000), 1e-9);
        assertEquals(0.0, service.costFor("p", "m", 0, 0), 1e-12);
    }

    @Test
    void freeIsExplicitZeroNotMissing() {
        stub(0.0, 0.0);
        assertEquals(0.0, service.costFor("p", "m", 500_000, 500_000), 1e-12);
    }

    @Test
    void halfKnownPricePairYieldsNoCost() {
        stub(null, 15.0);
        assertNull(service.costFor("p", "m", 1000, 1000));
        stub(3.0, null);
        assertNull(service.costFor("p", "m", 1000, 1000));
    }

    @Test
    void missingRowYieldsNoCost() {
        when(repo.findByProviderIdAndModelId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        assertNull(service.costFor("p", "m", 1000, 1000));
        assertNull(service.pricesFor(null, "m"));
        assertNull(service.pricesFor("p", null));
    }
}
