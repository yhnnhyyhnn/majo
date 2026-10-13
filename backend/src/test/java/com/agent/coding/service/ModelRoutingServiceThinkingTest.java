package com.agent.coding.service;

import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ActiveModelRepository;
import com.agent.coding.repository.ModelConfigRepository;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.repository.ProviderRepository;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Thinking-controls wiring (ADR-0016): the model-level thinking config and
 * provider-level generate_kwargs/custom_headers become real request
 * parameters through GenerateOptions — verified against the SDK's wire
 * mapping (thinkingBudget→thinking_budget, reasoningEffort→reasoning_effort,
 * additionalBodyParams→top-level body keys, additionalHeaders→HTTP headers).
 */
class ModelRoutingServiceThinkingTest {

    private final ProviderRepository providerRepo = mock(ProviderRepository.class);
    private final ProviderModelRepository providerModelRepo = mock(ProviderModelRepository.class);
    private final ModelRoutingService service = new ModelRoutingService(
            mock(ActiveModelRepository.class), providerRepo, providerModelRepo,
            mock(ModelConfigRepository.class));

    private ProviderEntity provider(String baseUrl, String style, String kwargs, String headers) {
        ProviderEntity p = new ProviderEntity();
        p.setId("dashscope");
        p.setName("DashScope");
        p.setBaseUrl(baseUrl);
        p.setApiKey("sk-test");
        p.setChatModel("OpenAIChatModel");
        p.setThinkingParamStyle(style);
        p.setGenerateKwargs(kwargs);
        p.setCustomHeaders(headers);
        return p;
    }

    private ProviderModelEntity model(String style, Boolean enabled, Integer budget, String effort) {
        ProviderModelEntity m = new ProviderModelEntity();
        m.setProviderId("dashscope");
        m.setModelId("qwen3-max");
        m.setThinkingParamStyle(style);
        m.setThinkingEnabled(enabled);
        m.setThinkingBudget(budget);
        m.setReasoningEffort(effort);
        return m;
    }

    private void stub(ProviderEntity p, ProviderModelEntity m) {
        when(providerRepo.findById(p.getId())).thenReturn(Optional.of(p));
        when(providerModelRepo.findByProviderIdAndModelId(anyString(), anyString()))
                .thenReturn(m == null ? Optional.empty() : Optional.of(m));
    }

    private static GenerateOptions optionsOf(OpenAIChatModel model) throws Exception {
        Field field = OpenAIChatModel.class.getDeclaredField("configuredOptions");
        field.setAccessible(true);
        return (GenerateOptions) field.get(model);
    }

    @Test
    void budgetStyleEnableSendsThinkingParams() throws Exception {
        stub(provider("https://dashscope.example/v1", "budget", null, null),
                model("budget", true, 8192, null));

        GenerateOptions options = optionsOf(service.buildOpenAIChatModel("dashscope", "qwen3-max"));

        assertNotNull(options);
        assertEquals(8192, options.getThinkingBudget());
        assertEquals(true, options.getAdditionalBodyParams().get("enable_thinking"));
    }

    @Test
    void budgetStyleDisableSendsDualOffSwitch() throws Exception {
        stub(provider("https://dashscope.example/v1", "budget", null, null),
                model("budget", false, 8192, null));

        GenerateOptions options = optionsOf(service.buildOpenAIChatModel("dashscope", "qwen3-max"));

        assertEquals(false, options.getAdditionalBodyParams().get("enable_thinking"));
        assertEquals(Map.of("type", "disabled"),
                options.getAdditionalBodyParams().get("thinking"));
        assertNull(options.getThinkingBudget(), "disabled must not carry a budget");
    }

    @Test
    void effortStyleSendsReasoningEffort() throws Exception {
        stub(provider("https://dashscope.example/v1", null, null, null),
                model("effort", true, null, "high"));

        GenerateOptions options = optionsOf(service.buildOpenAIChatModel("dashscope", "deepseek-v4-pro"));

        assertEquals("high", options.getReasoningEffort());
        assertTrue(options.getAdditionalBodyParams() == null
                || options.getAdditionalBodyParams().isEmpty());
    }

    @Test
    void effortStyleDisabledSendsNothing() throws Exception {
        stub(provider("https://dashscope.example/v1", null, null, null),
                model("effort", false, null, "high"));

        GenerateOptions options = optionsOf(service.buildOpenAIChatModel("dashscope", "deepseek-v4-pro"));

        assertNull(options.getReasoningEffort());
        assertTrue(options.getAdditionalBodyParams() == null
                || options.getAdditionalBodyParams().isEmpty());
    }

    @Test
    void unknownStyleSendsNoThinkingParams() throws Exception {
        stub(provider("https://openai.example/v1", null, null, null),
                model(null, true, 4096, "high"));

        GenerateOptions options = optionsOf(service.buildOpenAIChatModel("dashscope", "gpt-x"));

        // No style resolved → nothing wired; the model keeps the SDK's
        // default options (present but empty).
        assertNull(options.getThinkingBudget());
        assertNull(options.getReasoningEffort());
        assertTrue(options.getAdditionalBodyParams() == null
                || options.getAdditionalBodyParams().isEmpty());
    }

    @Test
    void generateKwargsMergeWithModelLevelAndExtraBodyPromotion() throws Exception {
        stub(provider("https://dashscope.example/v1", "budget",
                        "{\"extra_body\":{\"top_k\":5},\"max_tokens\":2048}", null),
                model("budget", null, null, null));
        // Model-level kwargs overlay the provider-level base.
        ProviderModelEntity m = model("budget", null, null, null);
        m.setGenerateKwargs("{\"temperature\":0.2}");
        when(providerModelRepo.findByProviderIdAndModelId(anyString(), anyString()))
                .thenReturn(Optional.of(m));

        GenerateOptions options = optionsOf(service.buildOpenAIChatModel("dashscope", "qwen3-max"));

        Map<String, Object> body = options.getAdditionalBodyParams();
        assertEquals(2048, body.get("max_tokens"));       // provider top-level key
        assertEquals(5, body.get("top_k"));               // promoted from extra_body
        assertEquals(0.2, body.get("temperature"));       // model-level overlay
    }

    @Test
    void customHeadersApplied() throws Exception {
        stub(provider("https://dashscope.example/v1", null, null,
                        "{\"X-Gateway\":\"majo\",\"X-Trace\":\"t-1\"}"),
                model(null, null, null, null));

        GenerateOptions options = optionsOf(service.buildOpenAIChatModel("dashscope", "qwen3-max"));

        assertEquals("majo", options.getAdditionalHeaders().get("X-Gateway"));
        assertEquals("t-1", options.getAdditionalHeaders().get("X-Trace"));
    }

    @Test
    void endpointPathOverrideApplied() throws Exception {
        ProviderEntity p = provider("https://generativelanguage.googleapis.com/v1beta/openai",
                null, null, null);
        p.setEndpointPath("/chat/completions");
        stub(p, model(null, null, null, null));

        GenerateOptions options = optionsOf(service.buildOpenAIChatModel("dashscope", "gemini-3-flash"));

        assertEquals("/chat/completions", options.getEndpointPath());
    }

    @Test
    void blankEndpointPathStaysDefault() throws Exception {
        ProviderEntity p = provider("https://api.openai.com/v1", null, null, null);
        p.setEndpointPath("  ");
        stub(p, model(null, null, null, null));

        // Blank path wires nothing; the model keeps the SDK default options.
        assertNull(optionsOf(service.buildOpenAIChatModel("dashscope", "gpt-x")).getEndpointPath());
    }

    @Test
    void brokenJsonFailsOpen() throws Exception {
        stub(provider("https://dashscope.example/v1", "budget",
                        "{not json", "{\"broken\""),
                model("budget", true, 1024, null));

        OpenAIChatModel built = service.buildOpenAIChatModel("dashscope", "qwen3-max");
        GenerateOptions options = optionsOf(built);

        assertNotNull(options, "thinking config still applies when kwargs JSON is broken");
        assertEquals(1024, options.getThinkingBudget());
        assertEquals(true, options.getAdditionalBodyParams().get("enable_thinking"));
        assertTrue(options.getAdditionalHeaders() == null
                || options.getAdditionalHeaders().isEmpty());
        assertFalse(options.getAdditionalBodyParams().containsKey("broken"));
    }
}
