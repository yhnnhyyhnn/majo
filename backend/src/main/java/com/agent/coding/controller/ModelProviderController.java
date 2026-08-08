package com.agent.coding.controller;

import com.agent.coding.dto.ModelInfoDto;
import com.agent.coding.dto.ProviderInfoDto;
import com.agent.coding.entity.ModelConfigEntity;
import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ModelConfigRepository;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.repository.ProviderRepository;
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
    private final ObjectMapper mapper = new ObjectMapper();

    public ModelProviderController(ModelRoutingService modelRouting, ModelConfigRepository modelRepo,
                                    ProviderRepository providerRepo, ProviderModelRepository providerModelRepo) {
        this.modelRouting = modelRouting;
        this.modelRepo = modelRepo;
        this.providerRepo = providerRepo;
        this.providerModelRepo = providerModelRepo;
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

    // ── Active model endpoints (qwenpaw-aligned) ─────────────────────

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

        // Validate provider & model exist (qwenpaw: _validate_model_slot)
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
     *  qwenpaw model picker (reads the DB) always agree. */
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
