package com.agent.coding.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "providers")
public class ProviderEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(name = "base_url", length = 512, nullable = false)
    private String baseUrl = "";

    @Column(name = "api_key", length = 512, nullable = false)
    private String apiKey = "";

    @Column(name = "chat_model", length = 64, nullable = false)
    private String chatModel = "OpenAIChatModel";

    @Column(name = "api_key_prefix", length = 64)
    private String apiKeyPrefix = "";

    @Column(name = "api_key_prefixes", columnDefinition = "CLOB")
    private String apiKeyPrefixes;

    @Column(name = "is_local", nullable = false)
    private Boolean isLocal = false;

    @Column(name = "freeze_url", nullable = false)
    private Boolean freezeUrl = false;

    @Column(name = "require_api_key", nullable = false)
    private Boolean requireApiKey = true;

    @Column(name = "is_custom", nullable = false)
    private Boolean isCustom = false;

    @Column(name = "support_model_discovery", nullable = false)
    private Boolean supportModelDiscovery = false;

    @Column(name = "support_connection_check", nullable = false)
    private Boolean supportConnectionCheck = true;

    @Column(name = "auth_mode", length = 32)
    private String authMode = "api_key";

    @Column(name = "supports_oauth")
    private Boolean supportsOauth = false;

    @Column(name = "oauth_connected")
    private Boolean oauthConnected = false;

    @Column(name = "is_free_tier")
    private Boolean isFreeTier = false;

    @Column(name = "provider_group", length = 64)
    private String providerGroup = "";

    @Column(name = "provider_group_name", length = 128)
    private String providerGroupName = "";

    @Column(name = "provider_variant", length = 64)
    private String providerVariant = "";

    @Column(name = "thinking_param_style", length = 16)
    private String thinkingParamStyle;

    @Column(name = "reasoning_effort_options", columnDefinition = "CLOB")
    private String reasoningEffortOptions;

    @Column(name = "thinking_budget_range", columnDefinition = "CLOB")
    private String thinkingBudgetRange;

    @Column(name = "generate_kwargs", columnDefinition = "CLOB")
    private String generateKwargs;

    @Column(name = "custom_headers", columnDefinition = "CLOB")
    private String customHeaders;

    @Column(name = "extra_fields", columnDefinition = "CLOB")
    private String extraFields;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public ProviderEntity() {}

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
    public String getApiKeyPrefix() { return apiKeyPrefix; }
    public void setApiKeyPrefix(String apiKeyPrefix) { this.apiKeyPrefix = apiKeyPrefix; }
    public String getApiKeyPrefixes() { return apiKeyPrefixes; }
    public void setApiKeyPrefixes(String apiKeyPrefixes) { this.apiKeyPrefixes = apiKeyPrefixes; }
    public Boolean getIsLocal() { return isLocal; }
    public void setIsLocal(Boolean isLocal) { this.isLocal = isLocal; }
    public Boolean getFreezeUrl() { return freezeUrl; }
    public void setFreezeUrl(Boolean freezeUrl) { this.freezeUrl = freezeUrl; }
    public Boolean getRequireApiKey() { return requireApiKey; }
    public void setRequireApiKey(Boolean requireApiKey) { this.requireApiKey = requireApiKey; }
    public Boolean getIsCustom() { return isCustom; }
    public void setIsCustom(Boolean isCustom) { this.isCustom = isCustom; }
    public Boolean getSupportModelDiscovery() { return supportModelDiscovery; }
    public void setSupportModelDiscovery(Boolean supportModelDiscovery) { this.supportModelDiscovery = supportModelDiscovery; }
    public Boolean getSupportConnectionCheck() { return supportConnectionCheck; }
    public void setSupportConnectionCheck(Boolean supportConnectionCheck) { this.supportConnectionCheck = supportConnectionCheck; }
    public String getAuthMode() { return authMode; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }
    public Boolean getSupportsOauth() { return supportsOauth; }
    public void setSupportsOauth(Boolean supportsOauth) { this.supportsOauth = supportsOauth; }
    public Boolean getOauthConnected() { return oauthConnected; }
    public void setOauthConnected(Boolean oauthConnected) { this.oauthConnected = oauthConnected; }
    public Boolean getIsFreeTier() { return isFreeTier; }
    public void setIsFreeTier(Boolean isFreeTier) { this.isFreeTier = isFreeTier; }
    public String getProviderGroup() { return providerGroup; }
    public void setProviderGroup(String providerGroup) { this.providerGroup = providerGroup; }
    public String getProviderGroupName() { return providerGroupName; }
    public void setProviderGroupName(String providerGroupName) { this.providerGroupName = providerGroupName; }
    public String getProviderVariant() { return providerVariant; }
    public void setProviderVariant(String providerVariant) { this.providerVariant = providerVariant; }
    public String getThinkingParamStyle() { return thinkingParamStyle; }
    public void setThinkingParamStyle(String thinkingParamStyle) { this.thinkingParamStyle = thinkingParamStyle; }
    public String getReasoningEffortOptions() { return reasoningEffortOptions; }
    public void setReasoningEffortOptions(String reasoningEffortOptions) { this.reasoningEffortOptions = reasoningEffortOptions; }
    public String getThinkingBudgetRange() { return thinkingBudgetRange; }
    public void setThinkingBudgetRange(String thinkingBudgetRange) { this.thinkingBudgetRange = thinkingBudgetRange; }
    public String getGenerateKwargs() { return generateKwargs; }
    public void setGenerateKwargs(String generateKwargs) { this.generateKwargs = generateKwargs; }
    public String getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(String customHeaders) { this.customHeaders = customHeaders; }
    public String getExtraFields() { return extraFields; }
    public void setExtraFields(String extraFields) { this.extraFields = extraFields; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
