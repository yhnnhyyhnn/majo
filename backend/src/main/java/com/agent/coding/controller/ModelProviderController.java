package com.agent.coding.controller;

import com.agent.coding.dto.ModelInfoDto;
import com.agent.coding.dto.ProviderInfoDto;
import com.agent.coding.entity.ModelConfigEntity;
import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ModelConfigRepository;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.repository.ProviderRepository;
import com.agent.coding.service.ModelDiscoveryService;
import com.agent.coding.service.ModelRoutingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ModelProviderController {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderController.class);

    private final ModelRoutingService modelRouting;
    private final ModelConfigRepository modelRepo;
    private final ProviderRepository providerRepo;
    private final ProviderModelRepository providerModelRepo;
    private final ModelDiscoveryService discoveryService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ModelProviderController(ModelRoutingService modelRouting, ModelConfigRepository modelRepo,
                                    ProviderRepository providerRepo, ProviderModelRepository providerModelRepo,
                                    ModelDiscoveryService discoveryService) {
        this.modelRouting = modelRouting;
        this.modelRepo = modelRepo;
        this.providerRepo = providerRepo;
        this.providerModelRepo = providerModelRepo;
        this.discoveryService = discoveryService;
    }

    @GetMapping("/models")
    public List<ProviderInfoDto> listProviders() {
        List<ProviderInfoDto> result = new ArrayList<>();
        for (ProviderEntity p : providerRepo.findAllByOrderByNameAsc()) {
            result.add(toProviderDto(p));
        }
        for (ModelConfigEntity e : modelRepo.findAll()) {
            result.add(toCustomProviderDto(e));
        }
        return result;
    }

    private ProviderInfoDto toProviderDto(ProviderEntity p) {
        var d = new ProviderInfoDto();
        d.setId(p.getId()); d.setName(p.getName());
        d.setBaseUrl(p.getBaseUrl()); d.setApiKey(p.getApiKey());
        d.setChatModel(p.getChatModel());
        d.setModels(toModelDtoList(providerModelRepo.findByProviderId(p.getId())));
        d.setExtraModels(List.of());
        d.setApiKeyPrefix(p.getApiKeyPrefix());
        d.setApiKeyPrefixes(parseJson(p.getApiKeyPrefixes()));
        d.setLocal(p.getIsLocal()); d.setFreezeUrl(p.getFreezeUrl());
        d.setRequireApiKey(p.getRequireApiKey()); d.setCustom(p.getIsCustom());
        d.setSupportModelDiscovery(p.getSupportModelDiscovery());
        d.setSupportConnectionCheck(p.getSupportConnectionCheck());
        d.setGenerateKwargs(parseJson(p.getGenerateKwargs()));
        d.setCustomHeaders(parseJson(p.getCustomHeaders()));
        d.setAuthMode(p.getAuthMode()); d.setSupportsOauth(p.getSupportsOauth());
        d.setOauthConnected(p.getOauthConnected()); d.setFreeTier(p.getIsFreeTier());
        d.setProviderGroup(p.getProviderGroup()); d.setProviderGroupName(p.getProviderGroupName());
        d.setProviderVariant(p.getProviderVariant());
        d.setThinkingParamStyle(p.getThinkingParamStyle());
        d.setReasoningEffortOptions(parseJson(p.getReasoningEffortOptions()));
        d.setThinkingBudgetRange(parseJson(p.getThinkingBudgetRange()));
        d.setMeta(parseJson(p.getExtraFields()));
        return d;
    }

    private List<ModelInfoDto> toModelDtoList(List<ProviderModelEntity> models) {
        List<ModelInfoDto> list = new ArrayList<>();
        for (ProviderModelEntity m : models) {
            var d = new ModelInfoDto();
            d.setId(m.getModelId()); d.setName(m.getName());
            d.setSupportsMultimodal(m.getSupportsMultimodal());
            d.setSupportsImage(m.getSupportsImage()); d.setSupportsVideo(m.getSupportsVideo());
            d.setProbeSource(m.getProbeSource()); d.setIsFree(m.getIsFree());
            d.setMaxTokens(m.getMaxTokens()); d.setMaxInputLength(m.getMaxInputLength());
            d.setMaxInputLengthConfigured(m.getMaxInputLengthConfigured());
            d.setGenerateKwargs(parseJson(m.getGenerateKwargs()));
            d.setRelayReasoning(m.getRelayReasoning());
            d.setThinkingEnabled(m.getThinkingEnabled());
            d.setThinkingBudget(m.getThinkingBudget());
            d.setReasoningEffort(m.getReasoningEffort());
            d.setThinkingParamStyle(m.getThinkingParamStyle());
            d.setReasoningEffortOptions(parseJson(m.getReasoningEffortOptions()));
            d.setThinkingBudgetRange(parseJson(m.getThinkingBudgetRange()));
            d.setHidden(m.getHidden());
            list.add(d);
        }
        return list;
    }

    private ProviderInfoDto toCustomProviderDto(ModelConfigEntity e) {
        var d = new ProviderInfoDto();
        d.setId(e.getId().toString()); d.setName(e.getName());
        d.setBaseUrl(e.getBaseUrl()); d.setApiKey(e.getApiKey().isEmpty() ? "" : "****");
        d.setChatModel("OpenAIChatModel");
        var model = new ModelInfoDto();
        model.setId(e.getModelName()); model.setName(e.getModelName());
        model.setSupportsMultimodal(false); model.setSupportsImage(false); model.setSupportsVideo(false);
        model.setProbeSource("custom"); model.setIsFree(false);
        model.setMaxTokens(128000); model.setMaxInputLength(128000);
        model.setMaxInputLengthConfigured(false); model.setGenerateKwargs(Map.of());
        model.setRelayReasoning(true);
        d.setModels(List.of(model));
        d.setExtraModels(List.of());
        d.setApiKeyPrefix(e.getApiKey().isEmpty() ? "" : e.getApiKey().substring(0, Math.min(4, e.getApiKey().length())) + "***");
        d.setApiKeyPrefixes(List.of());
        d.setLocal(false); d.setFreezeUrl(false); d.setRequireApiKey(true);
        d.setCustom(true); d.setSupportModelDiscovery(false); d.setSupportConnectionCheck(true);
        d.setGenerateKwargs(Map.of()); d.setCustomHeaders(Map.of());
        d.setAuthMode("api_key"); d.setSupportsOauth(false); d.setOauthConnected(false);
        d.setFreeTier(false);
        d.setProviderGroup(""); d.setProviderGroupName(""); d.setProviderVariant("");
        d.setReasoningEffortOptions(List.of("none","minimal","low","medium","high","xhigh"));
        d.setThinkingBudgetRange(List.of(1, 81920));
        d.setMeta(Map.of());
        return d;
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readValue(json, new TypeReference<Object>() {}); } catch (Exception e) { return json; }
    }

    // ── Active model endpoints (Majo-aligned) ─────────────────────

    @GetMapping("/models/active")
    public Map<String, Object> getActiveModels(
            @RequestParam(defaultValue = "effective") String scope,
            @RequestParam(required = false) String agent_id) {

        var slot = switch (scope) {
            case "global" -> modelRouting.resolveGlobalModel();
            case "agent" -> {
                if (agent_id == null || agent_id.isBlank()) {
                    throw new IllegalArgumentException("agent_id is required when scope is 'agent'");
                }
                yield modelRouting.resolveAgentModel(agent_id);
            }
            default -> modelRouting.resolveEffectiveModel(agent_id);
        };

        return buildActiveModelsInfo(slot);
    }

    @PutMapping("/models/active")
    public ResponseEntity<Map<String, Object>> setActiveModel(
            @RequestBody Map<String, Object> body) {

        String providerId = Objects.toString(body.get("provider_id"), "");
        String model = Objects.toString(body.get("model"), "");
        String scope = Objects.toString(body.get("scope"), "global");

        if (scope == null || scope.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("detail", "scope is required"));
        }

        // Validate provider & model exist
        if (!providerId.isBlank() && !model.isBlank()) {
            if (!modelExists(providerId, model)) {
                var prov = modelRouting.resolveProviderConnection(providerId);
                if (prov.baseUrl().isBlank()) {
                    return ResponseEntity.status(404)
                        .body(Map.of("detail", "Provider '" + providerId + "' not found."));
                }
                return ResponseEntity.status(400)
                    .body(Map.of("detail",
                        "Model '" + model + "' not found in provider '" + providerId + "'."));
            }
        } else {
            // Neither provider_id nor model provided → clear the slot
            modelRouting.setGlobalActiveModel("", "");
            return ResponseEntity.ok(buildActiveModelsInfo(null));
        }

        if ("agent".equals(scope)) {
            String agentId = Objects.toString(body.get("agent_id"), null);
            if (agentId == null || agentId.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("detail", "agent_id is required when scope is 'agent'"));
            }
            modelRouting.setAgentActiveModel(agentId, providerId, model);
            // Sync the agent profile active_model so the harness model picker
            // (backend_settings / agents.json) stays consistent with the DB.
            syncProfileActiveModel(agentId, providerId, model);
            var slot = modelRouting.resolveAgentModel(agentId);
            return ResponseEntity.ok(buildActiveModelsInfo(slot));
        }

        ModelRoutingService.ModelSlot slot;
        if ("global".equals(scope)) {
            modelRouting.setGlobalActiveModel(providerId, model);
            slot = modelRouting.resolveGlobalModel();
        } else {
            modelRouting.setGlobalActiveModel(providerId, model);
            slot = modelRouting.resolveGlobalModel();
        }
        return ResponseEntity.ok(buildActiveModelsInfo(slot));
    }

    @PutMapping("/models/{provider_id}/models/{model_id}/visibility")
    public ResponseEntity<?> setModelVisibility(@PathVariable String provider_id,
                                                @PathVariable String model_id,
                                                @RequestBody Map<String, Object> body) {
        var opt = providerModelRepo.findByProviderIdAndModelId(provider_id, model_id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(Map.of("detail", "Model '" + model_id + "' not found in provider '" + provider_id + "'"));
        }
        var m = opt.get();
        m.setHidden(Boolean.TRUE.equals(body.get("hidden")));
        providerModelRepo.save(m);
        var provider = providerRepo.findById(provider_id).orElse(null);
        return provider != null
                ? ResponseEntity.ok(toProviderDto(provider))
                : ResponseEntity.ok(Map.of("hidden", m.getHidden()));
    }

    private boolean modelExists(String providerId, String modelId) {
        // Check built-in providers first
        var bp = providerRepo.findById(providerId).orElse(null);
        if (bp != null) {
            return providerModelRepo.findByProviderIdAndModelId(providerId, modelId).isPresent();
        }
        // Check custom providers
        try {
            long id = Long.parseLong(providerId);
            var mc = modelRepo.findById(id).orElse(null);
            return mc != null && modelId.equals(mc.getModelName());
        } catch (NumberFormatException ignored) {}
        return false;
    }

    /** Mirror the DB active_model into the agent profile (agents.json) so the
     *  harness model picker (reads backend_settings/active_model) and the
     *  the model picker (reads the DB) always agree. */
    private void syncProfileActiveModel(String agentId, String providerId, String modelId) {
        try {
            var profile = com.agent.coding.agent.AgentStore.getProfile(agentId);
            if (profile == null) return;
            java.util.Map<String, Object> activeModel = new java.util.LinkedHashMap<>();
            activeModel.put("provider_id", providerId);
            activeModel.put("model", modelId);
            java.util.Map<String, Object> updates = new java.util.LinkedHashMap<>();
            updates.put("active_model", activeModel);
            com.agent.coding.agent.AgentStore.updateAgent(agentId, updates);
        } catch (Exception e) {
            log.warn("Failed to sync active_model to profile for agent {}", agentId, e);
        }
    }

    private Map<String, Object> buildActiveModelsInfo(ModelRoutingService.ModelSlot slot) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (slot != null && slot.hasBoth()) {
            Map<String, Object> llm = new LinkedHashMap<>();
            llm.put("provider_id", slot.providerId());
            llm.put("model", slot.modelId());
            result.put("active_llm", llm);
            Integer ctx = modelRouting.getContextSize(slot.providerId(), slot.modelId());
            result.put("effective_max_input_length", ctx);
        } else {
            result.put("active_llm", null);
            result.put("effective_max_input_length", null);
        }
        return result;
    }

    @PutMapping("/models/{provider_id}/config")
    public ResponseEntity<?> configureProvider(@PathVariable String provider_id,
                                                  @RequestBody Map<String, Object> body) {
        providerRepo.findById(provider_id).ifPresent(p -> {
            if (body.containsKey("api_key")) p.setApiKey(Objects.toString(body.get("api_key"), ""));
            if (body.containsKey("base_url")) p.setBaseUrl(Objects.toString(body.get("base_url"), ""));
            if (body.containsKey("chat_model")) p.setChatModel(Objects.toString(body.get("chat_model"), "OpenAIChatModel"));
            providerRepo.save(p);
        });
        return providerRepo.findById(provider_id)
            .<ResponseEntity<?>>map(p -> ResponseEntity.ok(toProviderDto(p)))
            .orElse(ResponseEntity.status(404).body(Map.of("error", "not found")));
    }

    @PostMapping("/models/custom-providers")
    public ProviderInfoDto createProvider(@RequestBody Map<String, String> body) {
        var e = new ModelConfigEntity();
        e.setName(body.getOrDefault("name", "Custom"));
        e.setApiKey(body.getOrDefault("apiKey", ""));
        e.setBaseUrl(body.getOrDefault("baseUrl", "https://api.openai.com/v1"));
        e.setModelName(body.getOrDefault("modelName", "gpt-4o-mini"));
        modelRepo.save(e);
        return toCustomProviderDto(e);
    }

    @DeleteMapping("/models/custom-providers/{provider_id}")
    public List<ProviderInfoDto> deleteProvider(@PathVariable String provider_id) {
        try { modelRepo.deleteById(Long.parseLong(provider_id)); } catch (NumberFormatException ignored) {}
        return listProviders();
    }

    @PostMapping("/models/{provider_id}/models")
    public Object addModel(@PathVariable String provider_id, @RequestBody Map<String, String> body) {
        try {
            Long id = Long.parseLong(provider_id);
            var e = modelRepo.findById(id).orElse(null);
            if (e != null) {
                e.setModelName(body.getOrDefault("model_id", body.getOrDefault("modelName", e.getModelName())));
                modelRepo.save(e);
                return toCustomProviderDto(e);
            }
        } catch (NumberFormatException ignored) {}
        return Map.of();
    }

    @DeleteMapping("/models/{provider_id}/models/{model_id}")
    public Object removeModel(@PathVariable String provider_id, @PathVariable String model_id) {
        try {
            Long id = Long.parseLong(provider_id);
            var e = modelRepo.findById(id).orElse(null);
            if (e != null) { e.setModelName("gpt-4o-mini"); modelRepo.save(e); return toCustomProviderDto(e); }
        } catch (NumberFormatException ignored) {}
        return Map.of();
    }

    @PutMapping("/models/{provider_id}/models/{model_id}/config")
    public ResponseEntity<?> configureModel(@PathVariable String provider_id, @PathVariable String model_id,
                                            @RequestBody Map<String, Object> body) {
        // Custom provider (numeric id stored in model_configs): update its model name.
        try {
            Long id = Long.parseLong(provider_id);
            var mc = modelRepo.findById(id).orElse(null);
            if (mc != null) {
                if (body.containsKey("model_name") || body.containsKey("model_id")) {
                    mc.setModelName(Objects.toString(body.getOrDefault("model_name", body.get("model_id")), mc.getModelName()));
                }
                modelRepo.save(mc);
                return ResponseEntity.ok(toCustomProviderDto(mc));
            }
        } catch (NumberFormatException ignored) {
        }
        // Built-in provider: update its model row.
        var opt = providerModelRepo.findByProviderIdAndModelId(provider_id, model_id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("detail", "Model '" + model_id + "' not found in provider '" + provider_id + "'"));
        }
        var m = opt.get();
        if (body.containsKey("max_tokens")) m.setMaxTokens(((Number) body.get("max_tokens")).intValue());
        if (body.containsKey("max_input_length")) {
            m.setMaxInputLength(((Number) body.get("max_input_length")).intValue());
            m.setMaxInputLengthConfigured(true);
        }
        if (body.containsKey("generate_kwargs")) m.setGenerateKwargs(writeJson(body.get("generate_kwargs")));
        if (body.containsKey("relay_reasoning")) m.setRelayReasoning(Boolean.TRUE.equals(body.get("relay_reasoning")));
        if (body.containsKey("thinking_enabled")) m.setThinkingEnabled(body.get("thinking_enabled") == null ? null : Boolean.TRUE.equals(body.get("thinking_enabled")));
        if (body.containsKey("thinking_budget")) m.setThinkingBudget(body.get("thinking_budget") == null ? null : ((Number) body.get("thinking_budget")).intValue());
        if (body.containsKey("reasoning_effort")) m.setReasoningEffort(body.get("reasoning_effort") == null ? null : String.valueOf(body.get("reasoning_effort")));
        providerModelRepo.save(m);
        var provider = providerRepo.findById(provider_id).orElse(null);
        if (provider != null) {
            return ResponseEntity.ok(toProviderDto(provider));
        }
        return ResponseEntity.ok(Map.of());
    }

    private String writeJson(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    private Map<String, String> resolveConnParams(String providerId, Map<String, Object> body) {
        var conn = modelRouting.resolveProviderConnection(providerId);
        String baseUrl = conn.baseUrl();
        String apiKey = conn.apiKey();
        if (body != null) {
            if (body.get("base_url") != null) baseUrl = String.valueOf(body.get("base_url"));
            if (body.get("api_key") != null) apiKey = String.valueOf(body.get("api_key"));
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("base_url", baseUrl);
        result.put("api_key", apiKey);
        return result;
    }

    @PostMapping("/models/{provider_id}/test")
    public Map<String, Object> testProvider(@PathVariable String provider_id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        var params = resolveConnParams(provider_id, body);
        boolean ok = discoveryService.testConnection(params.get("base_url"), params.get("api_key"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", ok);
        result.put("message", ok ? "Connection successful" : "Connection failed: invalid base_url or api_key");
        return result;
    }

    @PostMapping("/models/{provider_id}/models/test")
    public Map<String, Object> testModel(@PathVariable String provider_id,
                                         @RequestBody Map<String, Object> body) {
        var params = resolveConnParams(provider_id, body);
        String modelId = body.get("model_id") == null ? "" : String.valueOf(body.get("model_id"));
        boolean ok = discoveryService.testConnection(params.get("base_url"), params.get("api_key"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", ok);
        result.put("message", ok
                ? ("Model '" + modelId + "' connection successful")
                : "Model connection failed: invalid base_url or api_key");
        return result;
    }

    @PostMapping("/models/{provider_id}/discover")
    public Map<String, Object> discoverModels(@PathVariable String provider_id,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              @RequestParam(defaultValue = "true") boolean save) {
        var params = resolveConnParams(provider_id, body);
        var discovery = discoveryService.discover(provider_id, params.get("base_url"), params.get("api_key"), save);
        List<ModelInfoDto> models = new ArrayList<>();
        for (var entity : discovery.models()) {
            var dto = new ModelInfoDto();
            dto.setId(entity.getModelId());
            dto.setName(entity.getName());
            dto.setProbeSource(entity.getProbeSource());
            dto.setMaxTokens(entity.getMaxTokens());
            dto.setMaxInputLength(entity.getMaxInputLength());
            dto.setMaxInputLengthConfigured(entity.getMaxInputLengthConfigured());
            models.add(dto);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", discovery.success());
        result.put("message", discovery.message());
        result.put("models", models);
        result.put("added_count", save && discovery.success() ? models.size() : 0);
        return result;
    }

    @PostMapping("/models/{provider_id}/models/{model_id}/probe-multimodal")
    public Map<String, Object> probeMultimodal(@PathVariable String provider_id, @PathVariable String model_id) {
        // Best-effort: infer multimodal support from the model id heuristic.
        String lower = model_id.toLowerCase();
        boolean supportsImage = lower.contains("vision") || lower.contains("vl") || lower.contains("omni")
                || lower.contains("gpt-4o") || lower.contains("gemini");
        boolean supportsVideo = lower.contains("video") || lower.contains("gemini");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supports_image", supportsImage);
        result.put("supports_video", supportsVideo);
        result.put("supports_multimodal", supportsImage || supportsVideo);
        result.put("image_message", supportsImage ? "" : "Model does not support image input");
        result.put("video_message", supportsVideo ? "" : "Model does not support video input");
        return result;
    }
}
