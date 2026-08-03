package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LanguageResponse {
    @JsonProperty("language")
    private String language;

    public LanguageResponse() {}
    public LanguageResponse(String language) { this.language = language; }
    public String getLanguage() { return language; }
}
