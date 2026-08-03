package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class ActiveModelsResponse {
    @JsonProperty("active_llm") private Object activeLlm;
    @JsonProperty("effective_max_input_length") private Integer effectiveMaxInputLength;

    public ActiveModelsResponse() {}
    public ActiveModelsResponse(Object activeLlm, Integer effectiveMaxInputLength) {
        this.activeLlm = activeLlm; this.effectiveMaxInputLength = effectiveMaxInputLength;
    }
    public Object getActiveLlm() { return activeLlm; }
    public void setActiveLlm(Object activeLlm) { this.activeLlm = activeLlm; }
    public Integer getEffectiveMaxInputLength() { return effectiveMaxInputLength; }
    public void setEffectiveMaxInputLength(Integer effectiveMaxInputLength) { this.effectiveMaxInputLength = effectiveMaxInputLength; }
}
