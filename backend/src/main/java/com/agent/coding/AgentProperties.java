package com.agent.coding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.coding")
public class AgentProperties {
    private String apiKey;
    private String baseUrl = "https://api.openai.com/v1";
    private String modelName = "gpt-4o-mini";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String k) { apiKey = k; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String u) { baseUrl = u; }
    public String getModelName() { return modelName; }
    public void setModelName(String m) { modelName = m; }
}
