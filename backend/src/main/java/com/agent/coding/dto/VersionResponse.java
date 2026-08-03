package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class VersionResponse {
    @JsonProperty("version")
    private String version;

    public VersionResponse() {}
    public VersionResponse(String version) { this.version = version; }
    public String getVersion() { return version; }
}
