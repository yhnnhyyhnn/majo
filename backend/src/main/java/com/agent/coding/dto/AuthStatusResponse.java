package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AuthStatusResponse {
    @JsonProperty("enabled") private boolean enabled;

    public AuthStatusResponse() {}
    public AuthStatusResponse(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
