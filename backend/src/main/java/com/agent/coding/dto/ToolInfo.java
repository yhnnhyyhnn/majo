package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolInfo {

    @JsonProperty("name") private String name;
    @JsonProperty("enabled") private boolean enabled;
    @JsonProperty("description") private String description;
    @JsonProperty("async_execution") private boolean asyncExecution = true;
    @JsonProperty("icon") private String icon;
    @JsonProperty("requires_config") private boolean requiresConfig;
    @JsonProperty("config_fields") private Object configFields;
    @JsonProperty("config_values") private Object configValues;

    public ToolInfo() {}

    public ToolInfo(String name, boolean enabled, String description, String icon) {
        this.name = name; this.enabled = enabled; this.description = description; this.icon = icon;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isAsyncExecution() { return asyncExecution; }
    public void setAsyncExecution(boolean asyncExecution) { this.asyncExecution = asyncExecution; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public boolean isRequiresConfig() { return requiresConfig; }
    public void setRequiresConfig(boolean requiresConfig) { this.requiresConfig = requiresConfig; }
    public Object getConfigFields() { return configFields; }
    public void setConfigFields(Object configFields) { this.configFields = configFields; }
    public Object getConfigValues() { return configValues; }
    public void setConfigValues(Object configValues) { this.configValues = configValues; }
}
