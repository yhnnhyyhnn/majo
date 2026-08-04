package com.agent.coding;

import com.agent.coding.entity.SettingsEntity;
import com.agent.coding.repository.SettingsRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final SettingsRepository repository;

    private volatile String apiKey = "";
    private volatile String baseUrl = "https://api.openai.com/v1";
    private volatile String modelName = "gpt-4o-mini";
    private volatile String workspace = "";
    private volatile String audioMode = "auto";
    private volatile String transcriptionProviderType = "disabled";
    private volatile String transcriptionProviderId = "";
    private volatile boolean heartbeatEnabled = false;
    private volatile String heartbeatEvery = "6h";
    private volatile String heartbeatTarget = "main";
    private volatile int heartbeatTimeoutSeconds = 120;

    public SettingsService(SettingsRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void loadFromDb() {
        repository.findById(1).ifPresentOrElse(entity -> {
            this.apiKey = entity.getApiKey();
            this.baseUrl = entity.getBaseUrl();
            this.modelName = entity.getModelName();
            this.workspace = entity.getWorkspace();
            this.audioMode = entity.getAudioMode() != null ? entity.getAudioMode() : "auto";
            this.transcriptionProviderType = entity.getTranscriptionProviderType() != null ? entity.getTranscriptionProviderType() : "disabled";
            this.transcriptionProviderId = entity.getTranscriptionProviderId() != null ? entity.getTranscriptionProviderId() : "";
            this.heartbeatEnabled = entity.isHeartbeatEnabled();
            this.heartbeatEvery = entity.getHeartbeatEvery() != null ? entity.getHeartbeatEvery() : "6h";
            this.heartbeatTarget = entity.getHeartbeatTarget() != null ? entity.getHeartbeatTarget() : "main";
            this.heartbeatTimeoutSeconds = entity.getHeartbeatTimeoutSeconds() > 0 ? entity.getHeartbeatTimeoutSeconds() : 120;
            log.info("Settings loaded — baseUrl: {}, modelName: {}, audioMode: {}",
                baseUrl, modelName, audioMode);
        }, () -> {
            log.warn("No settings found in DB (id=1), using defaults");
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

    public String getAudioMode() { return audioMode; }
    @Transactional
    public void setAudioMode(String audioMode) { this.audioMode = audioMode; persist(); }

    public String getTranscriptionProviderType() { return transcriptionProviderType; }
    @Transactional
    public void setTranscriptionProviderType(String v) { this.transcriptionProviderType = v; persist(); }

    public String getTranscriptionProviderId() { return transcriptionProviderId; }
    @Transactional
    public void setTranscriptionProviderId(String v) { this.transcriptionProviderId = v; persist(); }

    public boolean isHeartbeatEnabled() { return heartbeatEnabled; }
    public String getHeartbeatEvery() { return heartbeatEvery; }
    public String getHeartbeatTarget() { return heartbeatTarget; }
    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }

    @Transactional
    public void setHeartbeatConfig(boolean enabled, String every, String target, int timeoutSec) {
        this.heartbeatEnabled = enabled;
        this.heartbeatEvery = every;
        this.heartbeatTarget = target;
        this.heartbeatTimeoutSeconds = timeoutSec;
        persist();
    }

    private void persist() {
        SettingsEntity entity = repository.findById(1).orElseGet(SettingsEntity::new);
        entity.setId(1);
        entity.setApiKey(apiKey);
        entity.setBaseUrl(baseUrl);
        entity.setModelName(modelName);
        entity.setWorkspace(workspace);
        entity.setAudioMode(audioMode);
        entity.setTranscriptionProviderType(transcriptionProviderType);
        entity.setTranscriptionProviderId(transcriptionProviderId);
        entity.setHeartbeatEnabled(heartbeatEnabled);
        entity.setHeartbeatEvery(heartbeatEvery);
        entity.setHeartbeatTarget(heartbeatTarget);
        entity.setHeartbeatTimeoutSeconds(heartbeatTimeoutSeconds);
        repository.save(entity);
        log.info("Settings persisted — baseUrl: {}, modelName: {}", baseUrl, modelName);
    }
}
