package com.agent.coding.agent;

import com.agent.coding.SettingsService;
import com.agent.coding.service.ModelRoutingService;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the shared {@link HarnessAgentFactory}: display-name
 * resolution and the model fallback policy (routing slot first, then
 * per-caller SETTINGS/NONE behaviour).
 */
class HarnessAgentFactoryTest {

    private HarnessAgentFactory factory(ModelRoutingService routing, SettingsService settings) {
        return new HarnessAgentFactory(routing, settings, new io.agentscope.core.tool.Toolkit());
    }

    // ── agentName ────────────────────────────────────────────────────

    @Test
    void agentNameFallsBackToAgentId() {
        HarnessAgentFactory factory = factory(
                Mockito.mock(ModelRoutingService.class), Mockito.mock(SettingsService.class));
        // "no-such-agent" has no profile in the registry → the id itself.
        assertEquals("no-such-agent", factory.agentName("no-such-agent"));
        assertEquals("subagent", factory.agentName("subagent"));
    }

    @Test
    void agentNameUsesProfileDisplayName() {
        try (var mocked = Mockito.mockStatic(AgentStore.class)) {
            mocked.when(() -> AgentStore.getProfile("writer"))
                    .thenReturn(Map.of("name", "写作助手"));
            HarnessAgentFactory factory = factory(
                    Mockito.mock(ModelRoutingService.class), Mockito.mock(SettingsService.class));
            assertEquals("写作助手", factory.agentName("writer"));
        }
    }

    @Test
    void agentNameIgnoresBlankProfileName() {
        try (var mocked = Mockito.mockStatic(AgentStore.class)) {
            mocked.when(() -> AgentStore.getProfile("blank"))
                    .thenReturn(Map.of("name", "  "));
            HarnessAgentFactory factory = factory(
                    Mockito.mock(ModelRoutingService.class), Mockito.mock(SettingsService.class));
            assertEquals("blank", factory.agentName("blank"));
        }
    }

    // ── modelFor ─────────────────────────────────────────────────────

    @Test
    void modelForUsesRoutingSlotWhenAvailable() {
        ModelRoutingService routing = Mockito.mock(ModelRoutingService.class);
        Mockito.when(routing.resolveEffectiveModel("a1"))
                .thenReturn(new ModelRoutingService.ModelSlot("p1", "m1"));
        Mockito.when(routing.buildOpenAIChatModel("p1", "m1"))
                .thenReturn(Mockito.mock(io.agentscope.extensions.model.openai.OpenAIChatModel.class));
        HarnessAgentFactory factory = factory(routing, Mockito.mock(SettingsService.class));

        assertNotNull(factory.modelFor("a1", HarnessAgentFactory.ModelFallback.NONE));
        assertNotNull(factory.modelFor("a1", HarnessAgentFactory.ModelFallback.SETTINGS));
    }

    @Test
    void modelForNoneReturnsNullWithoutSlot() {
        ModelRoutingService routing = Mockito.mock(ModelRoutingService.class);
        Mockito.when(routing.resolveEffectiveModel("a1"))
                .thenReturn(ModelRoutingService.ModelSlot.empty());
        HarnessAgentFactory factory = factory(routing, Mockito.mock(SettingsService.class));

        assertNull(factory.modelFor("a1", HarnessAgentFactory.ModelFallback.NONE));
    }

    @Test
    void modelForSettingsFallsBackToSettingsRow() {
        ModelRoutingService routing = Mockito.mock(ModelRoutingService.class);
        Mockito.when(routing.resolveEffectiveModel("a1"))
                .thenReturn(ModelRoutingService.ModelSlot.empty());
        SettingsService settings = Mockito.mock(SettingsService.class);
        Mockito.when(settings.getApiKey()).thenReturn("sk-test");
        Mockito.when(settings.getBaseUrl()).thenReturn("https://example.invalid/v1");
        Mockito.when(settings.getModelName()).thenReturn("test-model");
        HarnessAgentFactory factory = factory(routing, settings);

        io.agentscope.extensions.model.openai.OpenAIChatModel model =
                factory.modelFor("a1", HarnessAgentFactory.ModelFallback.SETTINGS);
        assertNotNull(model);
    }

    // ── builder ──────────────────────────────────────────────────────

    @Test
    void builderProducesBuildableAgent() {
        try (var mocked = Mockito.mockStatic(AgentStore.class)) {
            mocked.when(() -> AgentStore.getProfile("b1")).thenReturn(null);
            ModelRoutingService routing = Mockito.mock(ModelRoutingService.class);
            Mockito.when(routing.resolveEffectiveModel("b1"))
                    .thenReturn(ModelRoutingService.ModelSlot.empty());
            SettingsService settings = Mockito.mock(SettingsService.class);
            Mockito.when(settings.getApiKey()).thenReturn("sk-test");
            Mockito.when(settings.getBaseUrl()).thenReturn("https://example.invalid/v1");
            Mockito.when(settings.getModelName()).thenReturn("test-model");
            HarnessAgentFactory factory = factory(routing, settings);

            HarnessAgent agent = factory.builder("b1", "you are a test agent",
                    com.agent.coding.skill.SkillStore.WORKING_DIR,
                    factory.modelFor("b1", HarnessAgentFactory.ModelFallback.SETTINGS)).build();
            assertNotNull(agent);
        }
    }
}
