package com.agent.coding.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "provider_models")
public class ProviderModelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", length = 64, nullable = false)
    private String providerId;

    @Column(name = "model_id", length = 128, nullable = false)
    private String modelId;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(name = "supports_multimodal")
    private Boolean supportsMultimodal = false;

    @Column(name = "supports_image")
    private Boolean supportsImage = false;

    @Column(name = "supports_video")
    private Boolean supportsVideo = false;

    @Column(name = "probe_source", length = 64)
    private String probeSource;

    @Column(name = "is_free")
    private Boolean isFree = false;

    @Column(name = "max_tokens", nullable = false)
    private Integer maxTokens = 8192;

    @Column(name = "max_input_length", nullable = false)
    private Integer maxInputLength = 131072;

    @Column(name = "max_input_length_configured")
    private Boolean maxInputLengthConfigured = false;

    @Column(name = "relay_reasoning")
    private Boolean relayReasoning = true;

    @Column(name = "thinking_enabled")
    private Boolean thinkingEnabled;

    @Column(name = "thinking_budget")
    private Integer thinkingBudget;

    @Column(name = "reasoning_effort", length = 32)
    private String reasoningEffort;

    @Column(name = "thinking_param_style", length = 16)
    private String thinkingParamStyle;

    @Column(name = "generate_kwargs", columnDefinition = "CLOB")
    private String generateKwargs;

    @Column(name = "reasoning_effort_options", columnDefinition = "CLOB")
    private String reasoningEffortOptions;

    @Column(name = "thinking_budget_range", columnDefinition = "CLOB")
    private String thinkingBudgetRange;

    @Column(name = "extra_fields", columnDefinition = "CLOB")
    private String extraFields;

    public ProviderModelEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getSupportsMultimodal() { return supportsMultimodal; }
    public void setSupportsMultimodal(Boolean supportsMultimodal) { this.supportsMultimodal = supportsMultimodal; }
    public Boolean getSupportsImage() { return supportsImage; }
    public void setSupportsImage(Boolean supportsImage) { this.supportsImage = supportsImage; }
    public Boolean getSupportsVideo() { return supportsVideo; }
    public void setSupportsVideo(Boolean supportsVideo) { this.supportsVideo = supportsVideo; }
    public String getProbeSource() { return probeSource; }
    public void setProbeSource(String probeSource) { this.probeSource = probeSource; }
    public Boolean getIsFree() { return isFree; }
    public void setIsFree(Boolean isFree) { this.isFree = isFree; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Integer getMaxInputLength() { return maxInputLength; }
    public void setMaxInputLength(Integer maxInputLength) { this.maxInputLength = maxInputLength; }
    public Boolean getMaxInputLengthConfigured() { return maxInputLengthConfigured; }
    public void setMaxInputLengthConfigured(Boolean maxInputLengthConfigured) { this.maxInputLengthConfigured = maxInputLengthConfigured; }
    public Boolean getRelayReasoning() { return relayReasoning; }
    public void setRelayReasoning(Boolean relayReasoning) { this.relayReasoning = relayReasoning; }
    public Boolean getThinkingEnabled() { return thinkingEnabled; }
    public void setThinkingEnabled(Boolean thinkingEnabled) { this.thinkingEnabled = thinkingEnabled; }
    public Integer getThinkingBudget() { return thinkingBudget; }
    public void setThinkingBudget(Integer thinkingBudget) { this.thinkingBudget = thinkingBudget; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    public String getThinkingParamStyle() { return thinkingParamStyle; }
    public void setThinkingParamStyle(String thinkingParamStyle) { this.thinkingParamStyle = thinkingParamStyle; }
    public String getGenerateKwargs() { return generateKwargs; }
    public void setGenerateKwargs(String generateKwargs) { this.generateKwargs = generateKwargs; }
    public String getReasoningEffortOptions() { return reasoningEffortOptions; }
    public void setReasoningEffortOptions(String reasoningEffortOptions) { this.reasoningEffortOptions = reasoningEffortOptions; }
    public String getThinkingBudgetRange() { return thinkingBudgetRange; }
    public void setThinkingBudgetRange(String thinkingBudgetRange) { this.thinkingBudgetRange = thinkingBudgetRange; }
    public String getExtraFields() { return extraFields; }
    public void setExtraFields(String extraFields) { this.extraFields = extraFields; }
}
