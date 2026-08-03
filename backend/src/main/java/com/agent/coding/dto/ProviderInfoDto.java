package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class ProviderInfoDto {
    @JsonProperty("id") private String id;
    @JsonProperty("name") private String name;
    @JsonProperty("base_url") private String baseUrl;
    @JsonProperty("api_key") private String apiKey;
    @JsonProperty("chat_model") private String chatModel;
    @JsonProperty("models") private List<ModelInfoDto> models;
    @JsonProperty("extra_models") private List<ModelInfoDto> extraModels;
    @JsonProperty("api_key_prefix") private String apiKeyPrefix;
    @JsonProperty("api_key_prefixes") private Object apiKeyPrefixes;
    @JsonProperty("is_local") private boolean isLocal;
    @JsonProperty("freeze_url") private boolean freezeUrl;
    @JsonProperty("require_api_key") private boolean requireApiKey;
    @JsonProperty("is_custom") private boolean isCustom;
    @JsonProperty("support_model_discovery") private boolean supportModelDiscovery;
    @JsonProperty("support_connection_check") private boolean supportConnectionCheck;
    @JsonProperty("generate_kwargs") private Object generateKwargs;
    @JsonProperty("custom_headers") private Object customHeaders;
    @JsonProperty("auth_mode") private String authMode;
    @JsonProperty("supports_oauth") private boolean supportsOauth;
    @JsonProperty("oauth_connected") private boolean oauthConnected;
    @JsonProperty("is_free_tier") private boolean isFreeTier;
    @JsonProperty("provider_group") private String providerGroup;
    @JsonProperty("provider_group_name") private String providerGroupName;
    @JsonProperty("provider_variant") private String providerVariant;
    @JsonProperty("thinking_param_style") private String thinkingParamStyle;
    @JsonProperty("reasoning_effort_options") private Object reasoningEffortOptions;
    @JsonProperty("thinking_budget_range") private Object thinkingBudgetRange;
    @JsonProperty("meta") private Object meta;

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }
    public List<ModelInfoDto> getModels() { return models; }
    public void setModels(List<ModelInfoDto> models) { this.models = models; }
    public List<ModelInfoDto> getExtraModels() { return extraModels; }
    public void setExtraModels(List<ModelInfoDto> extraModels) { this.extraModels = extraModels; }
    public String getApiKeyPrefix() { return apiKeyPrefix; }
    public void setApiKeyPrefix(String apiKeyPrefix) { this.apiKeyPrefix = apiKeyPrefix; }
    public Object getApiKeyPrefixes() { return apiKeyPrefixes; }
    public void setApiKeyPrefixes(Object apiKeyPrefixes) { this.apiKeyPrefixes = apiKeyPrefixes; }
    public boolean isLocal() { return isLocal; }
    public void setLocal(boolean local) { isLocal = local; }
    public boolean isFreezeUrl() { return freezeUrl; }
    public void setFreezeUrl(boolean freezeUrl) { this.freezeUrl = freezeUrl; }
    public boolean isRequireApiKey() { return requireApiKey; }
    public void setRequireApiKey(boolean requireApiKey) { this.requireApiKey = requireApiKey; }
    public boolean isCustom() { return isCustom; }
    public void setCustom(boolean custom) { isCustom = custom; }
    public boolean isSupportModelDiscovery() { return supportModelDiscovery; }
    public void setSupportModelDiscovery(boolean supportModelDiscovery) { this.supportModelDiscovery = supportModelDiscovery; }
    public boolean isSupportConnectionCheck() { return supportConnectionCheck; }
    public void setSupportConnectionCheck(boolean supportConnectionCheck) { this.supportConnectionCheck = supportConnectionCheck; }
    public Object getGenerateKwargs() { return generateKwargs; }
    public void setGenerateKwargs(Object generateKwargs) { this.generateKwargs = generateKwargs; }
    public Object getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(Object customHeaders) { this.customHeaders = customHeaders; }
    public String getAuthMode() { return authMode; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }
    public boolean isSupportsOauth() { return supportsOauth; }
    public void setSupportsOauth(boolean supportsOauth) { this.supportsOauth = supportsOauth; }
    public boolean isOauthConnected() { return oauthConnected; }
    public void setOauthConnected(boolean oauthConnected) { this.oauthConnected = oauthConnected; }
    public boolean isFreeTier() { return isFreeTier; }
    public void setFreeTier(boolean freeTier) { isFreeTier = freeTier; }
    public String getProviderGroup() { return providerGroup; }
    public void setProviderGroup(String providerGroup) { this.providerGroup = providerGroup; }
    public String getProviderGroupName() { return providerGroupName; }
    public void setProviderGroupName(String providerGroupName) { this.providerGroupName = providerGroupName; }
    public String getProviderVariant() { return providerVariant; }
    public void setProviderVariant(String providerVariant) { this.providerVariant = providerVariant; }
    public String getThinkingParamStyle() { return thinkingParamStyle; }
    public void setThinkingParamStyle(String thinkingParamStyle) { this.thinkingParamStyle = thinkingParamStyle; }
    public Object getReasoningEffortOptions() { return reasoningEffortOptions; }
    public void setReasoningEffortOptions(Object reasoningEffortOptions) { this.reasoningEffortOptions = reasoningEffortOptions; }
    public Object getThinkingBudgetRange() { return thinkingBudgetRange; }
    public void setThinkingBudgetRange(Object thinkingBudgetRange) { this.thinkingBudgetRange = thinkingBudgetRange; }
    public Object getMeta() { return meta; }
    public void setMeta(Object meta) { this.meta = meta; }
}
