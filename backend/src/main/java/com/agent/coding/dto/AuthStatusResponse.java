package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AuthStatusResponse {
    @JsonProperty("enabled") private boolean enabled;
    @JsonProperty("has_users") private boolean hasUsers;

    public AuthStatusResponse() {}
    public AuthStatusResponse(boolean enabled) { this.enabled = enabled; }
    public AuthStatusResponse(boolean enabled, boolean hasUsers) {
        this.enabled = enabled;
        this.hasUsers = hasUsers;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isHasUsers() { return hasUsers; }
    public void setHasUsers(boolean hasUsers) { this.hasUsers = hasUsers; }
}
