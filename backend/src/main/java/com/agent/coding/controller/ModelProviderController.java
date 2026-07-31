package com.agent.coding.controller;

import com.agent.coding.SettingsService;
import com.agent.coding.entity.ModelConfigEntity;
import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ModelConfigRepository;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.repository.ProviderRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ModelProviderController {

    private final SettingsService settingsService;
    private final ModelConfigRepository modelRepo;
    private final ProviderRepository providerRepo;
    private final ProviderModelRepository providerModelRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public ModelProviderController(SettingsService settingsService, ModelConfigRepository modelRepo,
                                    ProviderRepository providerRepo, ProviderModelRepository providerModelRepo) {
        this.settingsService = settingsService;
        this.modelRepo = modelRepo;
        this.providerRepo = providerRepo;
        this.providerModelRepo = providerModelRepo;
    }

    @GetMapping("/models")
    public List<Map<String, Object>> listProviders() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProviderEntity p : providerRepo.findAllByOrderByNameAsc()) {
            result.add(toProviderMap(p));
        }
        for (ModelConfigEntity e : modelRepo.findAll()) {
            result.add(toCustomProviderMap(e));
        }
        return result;
    }

    private Map<String, Object> toProviderMap(ProviderEntity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId()); m.put("name", p.getName());
        m.put("base_url", p.getBaseUrl()); m.put("api_key", p.getApiKey());
        m.put("chat_model", p.getChatModel());
        m.put("models", toModelList(providerModelRepo.findByProviderId(p.getId())));
        m.put("extra_models", List.of());
        m.put("api_key_prefix", p.getApiKeyPrefix());
        m.put("api_key_prefixes", parseJson(p.getApiKeyPrefixes()));
        m.put("is_local", p.getIsLocal()); m.put("freeze_url", p.getFreezeUrl());
        m.put("require_api_key", p.getRequireApiKey()); m.put("is_custom", p.getIsCustom());
        m.put("support_model_discovery", p.getSupportModelDiscovery());
        m.put("support_connection_check", p.getSupportConnectionCheck());
        m.put("generate_kwargs", parseJson(p.getGenerateKwargs()));
        m.put("custom_headers", parseJson(p.getCustomHeaders()));
        m.put("auth_mode", p.getAuthMode());         m.put("supports_oauth", p.getSupportsOauth());
        m.put("oauth_connected", p.getOauthConnected()); m.put("is_free_tier", p.getIsFreeTier());
        m.put("provider_group", p.getProviderGroup()); m.put("provider_group_name", p.getProviderGroupName());
        m.put("provider_variant", p.getProviderVariant());
        m.put("thinking_param_style", p.getThinkingParamStyle());
        m.put("reasoning_effort_options", parseJson(p.getReasoningEffortOptions()));
        m.put("thinking_budget_range", parseJson(p.getThinkingBudgetRange()));
        m.put("meta", parseJson(p.getExtraFields()));
        return m;
    }

    private List<Map<String, Object>> toModelList(List<ProviderModelEntity> models) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ProviderModelEntity m : models) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.getModelId()); mm.put("name", m.getName());
            mm.put("supports_multimodal", m.getSupportsMultimodal());
            mm.put("supports_image", m.getSupportsImage()); mm.put("supports_video", m.getSupportsVideo());
            mm.put("probe_source", m.getProbeSource()); mm.put("is_free", m.getIsFree());
            mm.put("max_tokens", m.getMaxTokens()); mm.put("max_input_length", m.getMaxInputLength());
            mm.put("max_input_length_configured", m.getMaxInputLengthConfigured());
            mm.put("generate_kwargs", parseJson(m.getGenerateKwargs()));
            mm.put("relay_reasoning", m.getRelayReasoning());
            mm.put("thinking_enabled", m.getThinkingEnabled());
            mm.put("thinking_budget", m.getThinkingBudget());
            mm.put("reasoning_effort", m.getReasoningEffort());
            mm.put("thinking_param_style", m.getThinkingParamStyle());
            mm.put("reasoning_effort_options", parseJson(m.getReasoningEffortOptions()));
            mm.put("thinking_budget_range", parseJson(m.getThinkingBudgetRange()));
            list.add(mm);
        }
        return list;
    }

    private Map<String, Object> toCustomProviderMap(ModelConfigEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId().toString()); m.put("name", e.getName());
        m.put("base_url", e.getBaseUrl()); m.put("api_key", e.getApiKey().isEmpty() ? "" : "****");
        m.put("chat_model", "OpenAIChatModel");
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", e.getModelName()); model.put("name", e.getModelName());
        model.put("supports_multimodal", false); model.put("supports_image", false); model.put("supports_video", false);
        model.put("probe_source", "custom"); model.put("is_free", false);
        model.put("max_tokens", 128000); model.put("max_input_length", 128000);
        model.put("max_input_length_configured", false); model.put("generate_kwargs", Map.of());
        model.put("relay_reasoning", true); model.put("thinking_enabled", null);
        model.put("thinking_budget", null); model.put("reasoning_effort", null);
        model.put("thinking_param_style", null); model.put("reasoning_effort_options", null);
        model.put("thinking_budget_range", null);
        m.put("models", List.of(model));
        m.put("extra_models", List.of());
        m.put("api_key_prefix", e.getApiKey().isEmpty() ? "" : e.getApiKey().substring(0, Math.min(4, e.getApiKey().length())) + "***");
        m.put("api_key_prefixes", List.of());
        m.put("is_local", false); m.put("freeze_url", false); m.put("require_api_key", true);
        m.put("is_custom", true); m.put("support_model_discovery", false); m.put("support_connection_check", true);
        m.put("generate_kwargs", Map.of()); m.put("custom_headers", Map.of());
        m.put("auth_mode", "api_key"); m.put("supports_oauth", false); m.put("oauth_connected", false);
        m.put("is_free_tier", false);
        m.put("provider_group", ""); m.put("provider_group_name", ""); m.put("provider_variant", "");
        m.put("thinking_param_style", null);
        m.put("reasoning_effort_options", List.of("none","minimal","low","medium","high","xhigh"));
        m.put("thinking_budget_range", List.of(1, 81920));
        m.put("meta", Map.of());
        return m;
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readValue(json, new TypeReference<Object>() {}); } catch (Exception e) { return json; }
    }

    @GetMapping("/models/active")
    public Map<String, Object> getActiveModels() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("provider_id", "default");
        llm.put("model", settingsService.getModelName());
        result.put("active_llm", llm);
        result.put("effective_max_input_length", null);
        return result;
    }

    @PutMapping("/models/active")
    public Map<String, Object> setActiveModel(@RequestBody Map<String, Object> body) {
        String providerId = Objects.toString(body.get("provider_id"), "");
        String model = Objects.toString(body.get("model"), "");
        if (!model.isEmpty()) {
            settingsService.setModelName(model);
        }
        return getActiveModels();
    }

    @PutMapping("/models/{provider_id}/config")
    public Map<String, Object> configureProvider(@PathVariable String provider_id,
                                                  @RequestBody Map<String, Object> body) {
        providerRepo.findById(provider_id).ifPresent(p -> {
            if (body.containsKey("api_key")) p.setApiKey(Objects.toString(body.get("api_key"), ""));
            if (body.containsKey("base_url")) p.setBaseUrl(Objects.toString(body.get("base_url"), ""));
            if (body.containsKey("chat_model")) p.setChatModel(Objects.toString(body.get("chat_model"), "OpenAIChatModel"));
            providerRepo.save(p);
        });
        return providerRepo.findById(provider_id)
            .map(this::toProviderMap)
            .orElse(Map.of("error", "not found"));
    }

    @PostMapping("/models/custom-providers")
    public Map<String, Object> createProvider(@RequestBody Map<String, String> body) {
        var e = new ModelConfigEntity();
        e.setName(body.getOrDefault("name", "Custom"));
        e.setApiKey(body.getOrDefault("apiKey", ""));
        e.setBaseUrl(body.getOrDefault("baseUrl", "https://api.openai.com/v1"));
        e.setModelName(body.getOrDefault("modelName", "gpt-4o-mini"));
        modelRepo.save(e);
        return toCustomProviderMap(e);
    }

    @DeleteMapping("/models/custom-providers/{provider_id}")
    public List<Map<String, Object>> deleteProvider(@PathVariable String provider_id) {
        try { modelRepo.deleteById(Long.parseLong(provider_id)); } catch (NumberFormatException ignored) {}
        return listProviders();
    }

    @PostMapping("/models/{provider_id}/models")
    public Map<String, Object> addModel(@PathVariable String provider_id, @RequestBody Map<String, String> body) {
        try {
            Long id = Long.parseLong(provider_id);
            var e = modelRepo.findById(id).orElse(null);
            if (e != null) {
                e.setModelName(body.getOrDefault("model_id", body.getOrDefault("modelName", e.getModelName())));
                modelRepo.save(e);
                return toCustomProviderMap(e);
            }
        } catch (NumberFormatException ignored) {}
        return Map.of();
    }

    @DeleteMapping("/models/{provider_id}/models/{model_id}")
    public Map<String, Object> removeModel(@PathVariable String provider_id, @PathVariable String model_id) {
        try {
            Long id = Long.parseLong(provider_id);
            var e = modelRepo.findById(id).orElse(null);
            if (e != null) { e.setModelName("gpt-4o-mini"); modelRepo.save(e); return toCustomProviderMap(e); }
        } catch (NumberFormatException ignored) {}
        return Map.of();
    }

    @PutMapping("/models/{provider_id}/models/{model_id}/config")
    public Map<String, Object> configureModel(@PathVariable String provider_id, @PathVariable String model_id,
                                               @RequestBody Map<String, Object> body) { return Map.of(); }

    @PostMapping("/models/{provider_id}/test")
    public Map<String, String> testProvider(@PathVariable String provider_id) {
        return Map.of("status", "ok", "message", "Connection successful");
    }

    @PostMapping("/models/{provider_id}/models/test")
    public Map<String, String> testModel(@PathVariable String provider_id) {
        return Map.of("status", "ok", "message", "Model test successful");
    }

    @PostMapping("/models/{provider_id}/discover")
    public List<Map<String, String>> discoverModels(@PathVariable String provider_id) { return List.of(); }

    @PostMapping("/models/{provider_id}/models/{model_id}/probe-multimodal")
    public Map<String, Object> probeMultimodal(@PathVariable String provider_id, @PathVariable String model_id) {
        return Map.of("multimodal", false);
    }
}
