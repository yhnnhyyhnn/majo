package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

@SuppressWarnings("unused")
public class ModelInfoDto {
    @JsonProperty("id") private String id;
    @JsonProperty("name") private String name;
    @JsonProperty("supports_multimodal") private Boolean supportsMultimodal;
    @JsonProperty("supports_image") private Boolean supportsImage;
    @JsonProperty("supports_video") private Boolean supportsVideo;
    @JsonProperty("probe_source") private String probeSource;
    @JsonProperty("is_free") private Boolean isFree;
    @JsonProperty("max_tokens") private Integer maxTokens;
    @JsonProperty("max_input_length") private Integer maxInputLength;
    @JsonProperty("max_input_length_configured") private Boolean maxInputLengthConfigured;
    @JsonProperty("generate_kwargs") private Object generateKwargs;
    @JsonProperty("relay_reasoning") private Boolean relayReasoning;
    @JsonProperty("thinking_enabled") private Boolean thinkingEnabled;
    @JsonProperty("thinking_budget") private Integer thinkingBudget;
    @JsonProperty("reasoning_effort") private String reasoningEffort;
    @JsonProperty("thinking_param_style") private String thinkingParamStyle;
    @JsonProperty("reasoning_effort_options") private Object reasoningEffortOptions;
    @JsonProperty("thinking_budget_range") private Object thinkingBudgetRange;
@JsonProperty("hidden") private Boolean hidden = false;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
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
    public Object getGenerateKwargs() { return generateKwargs; }
    public void setGenerateKwargs(Object generateKwargs) { this.generateKwargs = generateKwargs; }
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
    public Object getReasoningEffortOptions() { return reasoningEffortOptions; }
    public void setReasoningEffortOptions(Object reasoningEffortOptions) { this.reasoningEffortOptions = reasoningEffortOptions; }
    public Object getThinkingBudgetRange() { return thinkingBudgetRange; }
    public void setThinkingBudgetRange(Object thinkingBudgetRange) { this.thinkingBudgetRange = thinkingBudgetRange; }
    public Boolean getHidden() { return hidden; }
    public void setHidden(Boolean hidden) { this.hidden = hidden; }
}
