package com.agent.coding.service;

import com.agent.coding.entity.*;
import com.agent.coding.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Java equivalent of the original ProviderManager for model routing.
 *  active_model is stored as {provider_id, model} in the DB; the actual
 *  connection (base_url, api_key) is read from the provider record at call
 *  time — not from any flat global settings. */
@Service
public class ModelRoutingService {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingService.class);

    private final ActiveModelRepository activeModelRepo;
    private final ProviderRepository providerRepo;
    private final ProviderModelRepository providerModelRepo;
    private final ModelConfigRepository modelConfigRepo;

    public ModelRoutingService(ActiveModelRepository activeModelRepo,
                                ProviderRepository providerRepo,
                                ProviderModelRepository providerModelRepo,
                                ModelConfigRepository modelConfigRepo) {
        this.activeModelRepo = activeModelRepo;
        this.providerRepo = providerRepo;
        this.providerModelRepo = providerModelRepo;
        this.modelConfigRepo = modelConfigRepo;
    }

    // ── Effective model resolution (scope) ──────────

    /** Resolve the effective active model for an agent.
     *  Agent-first, then global fallback — exactly's
     *  effective scope in get_active_models(). */
    public ModelSlot resolveEffectiveModel(String agentId) {
        var slot = resolveAgentModel(agentId);
        if (slot != null && slot.hasBoth()) {
            log.info("Resolved agent({}) active model: {} / {}", agentId, slot.providerId, slot.modelId);
            return slot;
        }
        var global = resolveGlobalModel();
        if (global != null && global.hasBoth()) {
            log.info("Resolved global active model: {} / {}", global.providerId, global.modelId);
            return global;
        }
        return ModelSlot.empty();
    }

    /** Resolve an agent's own active model. */
    public ModelSlot resolveAgentModel(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        // 1) agent-scoped active_model row in the DB (set via PUT /models/active)
        var fromDb = activeModelRepo.findByScopeAndAgentId("agent", agentId)
            .map(e -> new ModelSlot(e.getProviderId(), e.getModelId()))
            .filter(ModelSlot::hasBoth)
            .orElse(null);
        if (fromDb != null) return fromDb;
        // 2) agent profile active_model (set via PATCH /agents/{id}/backend-settings,
        //    the harness model picker path) — falls back when the DB has no
        //    agent-scoped entry so both selectors drive the same routing.
        try {
            var profile = com.agent.coding.agent.AgentStore.getProfile(agentId);
            if (profile != null) {
                Object am = profile.get("active_model");
                if (am instanceof Map<?, ?> m) {
                    Object pid = m.get("provider_id");
                    Object mid = m.get("model");
                    if (pid != null && mid != null
                            && !String.valueOf(pid).isBlank() && !String.valueOf(mid).isBlank()) {
                        return new ModelSlot(String.valueOf(pid), String.valueOf(mid));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read agent({}) profile active_model: {}", agentId, e.getMessage());
        }
        return null;
    }

    /** Resolve the global active model (.get_active_model()). */
    public ModelSlot resolveGlobalModel() {
        return activeModelRepo.findGlobal()
            .map(e -> new ModelSlot(e.getProviderId(), e.getModelId()))
            .orElse(null);
    }

    // ── Context size (.get_context_size(model_id)) ───

    /** Get the effective max input length (context window) for a model.
     *  Only real context metadata is consulted — the model's output limit
     *  (max_tokens) is a different dimension and must not stand in for the
     *  context window (QwenPaw output-vs-request-limit separation #7337).
     *  Returns null when unresolvable. */
    public Integer getContextSize(String providerId, String modelId) {
        if (providerId == null || modelId == null) return null;
        var provider = providerRepo.findById(providerId).orElse(null);
        if (provider != null) {
            var opt = providerModelRepo.findByProviderIdAndModelId(providerId, modelId);
            if (opt.isPresent()) {
                Integer ctx = opt.get().getMaxInputLength();
                if (ctx != null && ctx > 0) return ctx;
            }
            return null;
        }
        try {
            long id = Long.parseLong(providerId);
            var mc = modelConfigRepo.findById(id).orElse(null);
            if (mc != null) return 128000; // custom provider default
        } catch (NumberFormatException ignored) {}
        return null;
    }

    // ── Provider resolution ──────────────────────────────────────────

    /** Resolve a provider's info: base_url + api_key from providers or custom model_configs.
     *  This is what the original provider.get_chat_model_instance() reads from. */
    public ProviderConnection resolveProviderConnection(String providerId) {
        if (providerId == null || providerId.isBlank()) return new ProviderConnection("", "", "OpenAIChatModel");

        // Built-in provider
        var bp = providerRepo.findById(providerId).orElse(null);
        if (bp != null) {
            String model = bp.getChatModel() != null ? bp.getChatModel() : "OpenAIChatModel";
            return new ProviderConnection(bp.getBaseUrl(), bp.getApiKey(), model);
        }

        // Custom provider (model_configs)
        try {
            long id = Long.parseLong(providerId);
            var mc = modelConfigRepo.findById(id).orElse(null);
            if (mc != null) {
                return new ProviderConnection(mc.getBaseUrl(), mc.getApiKey(), "OpenAIChatModel");
            }
        } catch (NumberFormatException ignored) {}

        log.warn("Provider '{}' not found in providers or model_configs", providerId);
        return new ProviderConnection("", "", "OpenAIChatModel");
    }

    /** Check whether a provider has a specific model (.has_model). */
    public boolean hasModel(String providerId, String modelId) {
        if (providerId == null || modelId == null) return false;
        var bp = providerRepo.findById(providerId).orElse(null);
        if (bp != null) {
            var pm = providerModelRepo.findByProviderIdAndModelId(providerId, modelId);
            if (pm.isPresent()) return true;
            // Also check extra_models — for now, check model_id pattern match
            return false;
        }
        // Custom provider: model matches the configured modelName
        try {
            long id = Long.parseLong(providerId);
            var mc = modelConfigRepo.findById(id).orElse(null);
            if (mc != null) {
                return modelId.equals(mc.getModelName());
            }
        } catch (NumberFormatException ignored) {}
        return false;
    }

    // ── Model instantiation (.get_chat_model_instance) ─

    /** Build an OpenAIChatModel from the provider's connection info at call time.
     *  This replaces the flat SettingsService approach — the model is built from
     *  the ACTIVE PROVIDER's base_url/api_key, exactly as the original does. */
    public OpenAIChatModel buildOpenAIChatModel(String providerId, String modelId) {
        var conn = resolveProviderConnection(providerId);
        if (conn.baseUrl.isBlank()) {
            log.warn("No base_url for provider '{}', using default", providerId);
            return OpenAIChatModel.builder()
                .apiKey(conn.apiKey)
                .baseUrl("https://api.openai.com/v1")
                .modelName(modelId != null ? modelId : "gpt-4o-mini")
                .build();
        }
        log.info("Building chat model for {}/{} via {}", providerId, modelId, conn.baseUrl);
        var builder = OpenAIChatModel.builder()
            .apiKey(conn.apiKey)
            .baseUrl(conn.baseUrl)
            .modelName(modelId != null ? modelId : "gpt-4o-mini");
        // Carry the provider-resolved context window onto the model instance
        // instead of leaving the framework default in place (QwenPaw #7652).
        Integer ctx = getContextSize(providerId, modelId);
        if (ctx != null && ctx > 0) {
            builder.contextWindowSize(ctx);
        }
        // Thinking controls + generate_kwargs + custom_headers (ADR-0016):
        // the configured UI values become real request parameters.
        GenerateOptions options = resolveThinkingOptions(providerId, modelId);
        if (options != null) {
            builder.generateOptions(options);
        }
        return builder.build();
    }

    // ── Thinking controls wiring (ADR-0016) ──────────────────────────

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Resolve the per-model {@link GenerateOptions} carrying the thinking
     * controls (ADR-0016): model-level thinking_enabled/budget/effort over
     * the effective thinking_param_style (model, then provider), merged onto
     * provider- and model-level generate_kwargs (extra_body promoted), plus
     * the provider's custom_headers. Returns null when nothing is configured
     * — the request body then stays byte-identical to pre-ADR-0016.
     */
    GenerateOptions resolveThinkingOptions(String providerId, String modelId) {
        ProviderEntity provider = providerId != null
                ? providerRepo.findById(providerId).orElse(null) : null;
        ProviderModelEntity model = providerId != null && modelId != null
                ? providerModelRepo.findByProviderIdAndModelId(providerId, modelId).orElse(null) : null;

        Map<String, Object> body = new LinkedHashMap<>();
        mergeKwargs(body, provider != null ? provider.getGenerateKwargs() : null);
        mergeKwargs(body, model != null ? model.getGenerateKwargs() : null);

        GenerateOptions.Builder options = GenerateOptions.builder();
        boolean firstClass = false;
        String style = effectiveStyle(provider, model);
        if (model != null && style != null) {
            Boolean enabled = model.getThinkingEnabled();
            if ("budget".equals(style)) {
                if (Boolean.TRUE.equals(enabled)) {
                    body.put("enable_thinking", true);
                    Integer budget = model.getThinkingBudget();
                    if (budget != null && budget > 0) {
                        options.thinkingBudget(budget);
                        firstClass = true;
                    }
                } else if (Boolean.FALSE.equals(enabled)) {
                    // Qwen-style and DeepSeek-style off switches together
                    // (qwenpaw compat _consume_disable_thinking).
                    body.put("enable_thinking", false);
                    body.put("thinking", Map.of("type", "disabled"));
                }
            } else if ("effort".equals(style) && Boolean.TRUE.equals(enabled)) {
                String effort = model.getReasoningEffort();
                if (effort != null && !effort.isBlank()) {
                    options.reasoningEffort(effort);
                    firstClass = true;
                }
                // effort + disabled → send nothing: strict endpoints 400 on
                // unknown body params; not opting in is the safe off.
            }
        }

        boolean any = firstClass;
        if (!body.isEmpty()) {
            options.additionalBodyParams(body);
            any = true;
        }
        Map<String, String> headers = parseHeaders(provider != null ? provider.getCustomHeaders() : null);
        if (!headers.isEmpty()) {
            options.additionalHeaders(headers);
            any = true;
        }
        if (!any) {
            return null;
        }
        log.info("Thinking controls wired for {}/{}: style={}, bodyKeys={}, headers={}",
                providerId, modelId, style, body.keySet(), headers.keySet());
        return options.build();
    }

    private static String effectiveStyle(ProviderEntity provider, ProviderModelEntity model) {
        if (model != null && model.getThinkingParamStyle() != null
                && !model.getThinkingParamStyle().isBlank()) {
            return model.getThinkingParamStyle();
        }
        if (provider != null && provider.getThinkingParamStyle() != null
                && !provider.getThinkingParamStyle().isBlank()) {
            return provider.getThinkingParamStyle();
        }
        return null;
    }

    /** Merge generate_kwargs over {@code body}; a nested extra_body object is promoted first. */
    private static void mergeKwargs(Map<String, Object> body, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Map<String, Object> parsed = JSON.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            Object extra = parsed.remove("extra_body");
            if (extra instanceof Map<?, ?> extraBody) {
                for (var e : extraBody.entrySet()) {
                    body.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            body.putAll(parsed); // direct top-level keys win
        } catch (Exception e) {
            log.warn("Ignoring unparsable generate_kwargs: {}", e.getMessage());
        }
    }

    private static Map<String, String> parseHeaders(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : parsed.entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return out;
        } catch (Exception e) {
            log.warn("Ignoring unparsable custom_headers: {}", e.getMessage());
            return Map.of();
        }
    }

    // ── Active model persistence (+ agent scope) ─

    @Transactional
    public void setGlobalActiveModel(String providerId, String modelId) {
        var entity = activeModelRepo.findGlobal().orElseGet(() -> {
            var e = new ActiveModelEntity();
            e.setScope("global");
            return e;
        });
        entity.setProviderId(providerId != null ? providerId : "");
        entity.setModelId(modelId != null ? modelId : "");
        activeModelRepo.save(entity);
        log.info("Global active model set to {}/{}", providerId, modelId);
    }

    @Transactional
    public void setAgentActiveModel(String agentId, String providerId, String modelId) {
        if (agentId == null || agentId.isBlank()) {
            setGlobalActiveModel(providerId, modelId);
            return;
        }
        var entity = activeModelRepo.findByScopeAndAgentId("agent", agentId).orElseGet(() -> {
            var e = new ActiveModelEntity();
            e.setScope("agent");
            e.setAgentId(agentId);
            return e;
        });
        entity.setProviderId(providerId != null ? providerId : "");
        entity.setModelId(modelId != null ? modelId : "");
        activeModelRepo.save(entity);
        log.info("Agent({}) active model set to {}/{}", agentId, providerId, modelId);
    }

    // ── DTOs ─────────────────────────────────────────────────────────

    /** Model slot: provider_id + model.'s ModelSlotConfig. */
    public record ModelSlot(String providerId, String modelId) {
        public static ModelSlot empty() { return new ModelSlot("", ""); }
        public boolean hasBoth() {
            return providerId != null && !providerId.isBlank()
                && modelId != null && !modelId.isBlank();
        }
    }

    /** Resolved provider connection info. Mirrors what provider.get_chat_model_instance() reads. */
    public record ProviderConnection(String baseUrl, String apiKey, String chatModel) {}

    // ── Harness model catalog─────────

    /**
     * Build the harness model catalog from all configured providers/models,
     * mirroring the {@code HarnessModel} response shape the frontend
     * HarnessModelSelector consumes:
     * {@code {id, name, description, is_default, reasoning_efforts, default_reasoning_effort}}.
     * Model ids are prefixed with the provider id ({@code providerId/modelId})
     * so a selection round-trips into {@code setAgentActiveModel}.
     */
    public List<Map<String, Object>> listHarnessModels() {
        java.util.LinkedHashMap<String, Map<String, Object>> byId = new java.util.LinkedHashMap<>();
        for (ProviderEntity p : providerRepo.findAll()) {
            String pid = p.getId();
            String pname = p.getName();
            var models = providerModelRepo.findByProviderId(pid);
            if (models.isEmpty()) {
                continue;
            }
            boolean first = true;
            for (ProviderModelEntity m : models) {
                String mid = m.getModelId();
                String fullId = pid + "/" + mid;
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("id", fullId);
                entry.put("name", (m.getName() != null && !m.getName().isBlank())
                        ? m.getName() : mid);
                entry.put("description", pname);
                entry.put("is_default", first && models.size() > 0);
                entry.put("reasoning_efforts", parseEffortList(m.getReasoningEffortOptions()));
                entry.put("default_reasoning_effort", m.getReasoningEffort());
                byId.putIfAbsent(fullId, entry);
                first = false;
            }
        }
        // Custom single-model providers (ModelConfigEntity)
        for (ModelConfigEntity e : modelConfigRepo.findAll()) {
            String pid = "model-" + e.getId();
            String mid = e.getModelName();
            String fullId = pid + "/" + mid;
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("id", fullId);
            entry.put("name", mid);
            entry.put("description", e.getName());
            entry.put("is_default", false);
            entry.put("reasoning_efforts", List.of());
            entry.put("default_reasoning_effort", null);
            byId.putIfAbsent(fullId, entry);
        }
        if (!byId.isEmpty() && byId.values().stream().noneMatch(e -> Boolean.TRUE.equals(e.get("is_default")))) {
            Map<String, Object> first = byId.values().iterator().next();
            first.put("is_default", true);
        }
        return new java.util.ArrayList<>(byId.values());
    }

    private static List<String> parseEffortList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
            List<String> out = new java.util.ArrayList<>();
            for (Object o : parsed) out.add(String.valueOf(o));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
