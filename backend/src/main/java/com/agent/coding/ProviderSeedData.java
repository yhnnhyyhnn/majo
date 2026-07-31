package com.agent.coding;

import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.repository.ProviderRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Component
public class ProviderSeedData {

    private final ProviderRepository providerRepo;
    private final ProviderModelRepository modelRepo;

    public ProviderSeedData(ProviderRepository providerRepo, ProviderModelRepository modelRepo) {
        this.providerRepo = providerRepo;
        this.modelRepo = modelRepo;
    }

    @PostConstruct
    @Transactional
    void seed() {
        if (providerRepo.count() > 0) return;
        try {
            var mapper = new ObjectMapper();
            List<Map<String, Object>> list = mapper.readValue(
                new ClassPathResource("config/providers.json").getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> p : list) {
                var e = new ProviderEntity();
                e.setId(s(p, "id")); e.setName(s(p, "name")); e.setBaseUrl(s(p, "base_url"));
                e.setApiKey(s(p, "api_key")); e.setChatModel(s(p, "chat_model"));
                e.setApiKeyPrefix(s(p, "api_key_prefix")); e.setApiKeyPrefixes(j(p.get("api_key_prefixes")));
                e.setIsLocal(b(p, "is_local")); e.setFreezeUrl(b(p, "freeze_url"));
                e.setRequireApiKey(b(p, "require_api_key")); e.setIsCustom(b(p, "is_custom"));
                e.setSupportModelDiscovery(b(p, "support_model_discovery"));
                e.setSupportConnectionCheck(b(p, "support_connection_check"));
                e.setAuthMode(s(p, "auth_mode")); e.setSupportsOauth(b(p, "supports_oauth"));
                e.setIsFreeTier(b(p, "is_free_tier")); e.setProviderGroup(s(p, "provider_group"));
                e.setProviderGroupName(s(p, "provider_group_name")); e.setProviderVariant(s(p, "provider_variant"));
                e.setThinkingParamStyle((String) p.get("thinking_param_style"));
                e.setReasoningEffortOptions(j(p.get("reasoning_effort_options")));
                e.setThinkingBudgetRange(j(p.get("thinking_budget_range")));
                e.setGenerateKwargs(j(p.get("generate_kwargs")));
                e.setCustomHeaders(j(p.get("custom_headers")));
                e.setExtraFields(j(p.get("meta")));
                providerRepo.save(e);

                @SuppressWarnings("unchecked")
                var models = (List<Map<String, Object>>) p.getOrDefault("models", List.of());
                for (Map<String, Object> m : models) {
                    var me = new ProviderModelEntity();
                    me.setProviderId(s(p, "id")); me.setModelId(s(m, "id")); me.setName(s(m, "name"));
                    me.setSupportsMultimodal(b(m, "supports_multimodal"));
                    me.setSupportsImage(b(m, "supports_image")); me.setSupportsVideo(b(m, "supports_video"));
                    me.setProbeSource((String) m.get("probe_source")); me.setIsFree(b(m, "is_free"));
                    me.setMaxTokens((Integer) m.getOrDefault("max_tokens", 8192));
                    me.setMaxInputLength((Integer) m.getOrDefault("max_input_length", 131072));
                    me.setMaxInputLengthConfigured(b(m, "max_input_length_configured"));
                    me.setRelayReasoning(b(m, "relay_reasoning"));
                    me.setThinkingEnabled((Boolean) m.get("thinking_enabled"));
                    me.setThinkingBudget((Integer) m.get("thinking_budget"));
                    me.setReasoningEffort((String) m.get("reasoning_effort"));
                    me.setThinkingParamStyle((String) m.get("thinking_param_style"));
                    me.setGenerateKwargs(j(m.get("generate_kwargs")));
                    me.setReasoningEffortOptions(j(m.get("reasoning_effort_options")));
                    me.setThinkingBudgetRange(j(m.get("thinking_budget_range")));
                    modelRepo.save(me);
                }
            }
        } catch (Exception ignored) {}
    }

    private static String s(Map<String, Object> m, String k) { return Objects.toString(m.getOrDefault(k, ""), ""); }
    private static Boolean b(Map<String, Object> m, String k) { return (Boolean) m.getOrDefault(k, false); }
    private static String j(Object o) {
        if (o == null) return null;
        try { return new ObjectMapper().writeValueAsString(o); } catch (Exception e) { return o.toString(); }
    }
}
