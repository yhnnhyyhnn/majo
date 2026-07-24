package com.agent.coding.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "settings")
public class SettingsEntity {

    @Id
    private Integer id = 1;

    @Column(name = "api_key", length = 512, nullable = false)
    private String apiKey = "";

    @Column(name = "base_url", length = 512, nullable = false)
    private String baseUrl = "https://api.openai.com/v1";

    @Column(name = "model_name", length = 256, nullable = false)
    private String modelName = "gpt-4o-mini";

    @Column(name = "workspace", length = 1024, nullable = false)
    private String workspace = "";

    public SettingsEntity() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }
}
