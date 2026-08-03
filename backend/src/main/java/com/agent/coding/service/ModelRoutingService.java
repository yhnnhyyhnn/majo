package com.agent.coding.service;

import com.agent.coding.entity.*;
import com.agent.coding.repository.*;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Java equivalent of qwenpaw's ProviderManager for model routing.
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

    // ── Effective model resolution (qwenpaw: effective scope) ──────────

    /** Resolve the effective active model for an agent.
     *  Agent-first, then global fallback — exactly matching qwenpaw's
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

    /** Resolve an agent's own active model (qwenpaw: _load_agent_model). */
    public ModelSlot resolveAgentModel(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        return activeModelRepo.findByScopeAndAgentId("agent", agentId)
            .map(e -> new ModelSlot(e.getProviderId(), e.getModelId()))
            .orElse(null);
    }

    /** Resolve the global active model (qwenpaw: manager.get_active_model()). */
    public ModelSlot resolveGlobalModel() {
        return activeModelRepo.findGlobal()
            .map(e -> new ModelSlot(e.getProviderId(), e.getModelId()))
            .orElse(null);
    }

    // ── Context size (qwenpaw: provider.get_context_size(model_id)) ───

    /** Get the effective max input length (context window) for a model.
     *  Returns null when unresolvable — mirrors qwenpaw. */
    public Integer getContextSize(String providerId, String modelId) {
        if (providerId == null || modelId == null) return null;
        var provider = providerRepo.findById(providerId).orElse(null);
        if (provider != null) {
            var opt = providerModelRepo.findByProviderIdAndModelId(providerId, modelId);
            if (opt.isPresent()) {
                Integer ctx = opt.get().getMaxInputLength();
                if (ctx != null && ctx > 0) return ctx;
            }
            // Fallback: resolve via max_tokens
            return opt.map(ProviderModelEntity::getMaxTokens).orElse(null);
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
     *  This is what qwenpaw's provider.get_chat_model_instance() reads from. */
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

    /** Check whether a provider has a specific model (qwenpaw: provider.has_model). */
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

    // ── Model instantiation (qwenpaw: provider.get_chat_model_instance) ─

    /** Build an OpenAIChatModel from the provider's connection info at call time.
     *  This replaces the flat SettingsService approach — the model is built from
     *  the ACTIVE PROVIDER's base_url/api_key, exactly as qwenpaw does. */
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
        return OpenAIChatModel.builder()
            .apiKey(conn.apiKey)
            .baseUrl(conn.baseUrl)
            .modelName(modelId != null ? modelId : "gpt-4o-mini")
            .build();
    }

    // ── Active model persistence (qwenpaw: activate_model + agent scope) ─

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

    /** Model slot: provider_id + model. Mirrors qwenpaw's ModelSlotConfig. */
    public record ModelSlot(String providerId, String modelId) {
        public static ModelSlot empty() { return new ModelSlot("", ""); }
        public boolean hasBoth() {
            return providerId != null && !providerId.isBlank()
                && modelId != null && !modelId.isBlank();
        }
    }

    /** Resolved provider connection info. Mirrors what provider.get_chat_model_instance() reads. */
    public record ProviderConnection(String baseUrl, String apiKey, String chatModel) {}
}
