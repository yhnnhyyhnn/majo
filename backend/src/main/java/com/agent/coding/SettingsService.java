package com.agent.coding;

import com.agent.coding.entity.SettingsEntity;
import com.agent.coding.repository.SettingsRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {

    private final SettingsRepository repository;

    private volatile String apiKey = "";
    private volatile String baseUrl = "https://api.openai.com/v1";
    private volatile String modelName = "gpt-4o-mini";
    private volatile String workspace = "";

    public SettingsService(SettingsRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void loadFromDb() {
        repository.findById(1).ifPresent(entity -> {
            this.apiKey = entity.getApiKey();
            this.baseUrl = entity.getBaseUrl();
            this.modelName = entity.getModelName();
            this.workspace = entity.getWorkspace();
        });
    }

    public String getApiKey() { return apiKey; }

    @Transactional
    public void setApiKey(String apiKey) { this.apiKey = apiKey; persist(); }

    public String getBaseUrl() { return baseUrl; }

    @Transactional
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; persist(); }

    public String getModelName() { return modelName; }

    @Transactional
    public void setModelName(String modelName) { this.modelName = modelName; persist(); }

    public String getWorkspace() { return workspace; }

    @Transactional
    public void setWorkspace(String workspace) { this.workspace = workspace; persist(); }

    private void persist() {
        SettingsEntity entity = repository.findById(1).orElseGet(SettingsEntity::new);
        entity.setId(1);
        entity.setApiKey(apiKey);
        entity.setBaseUrl(baseUrl);
        entity.setModelName(modelName);
        entity.setWorkspace(workspace);
        repository.save(entity);
    }
}
