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

    @Column(name = "audio_mode", length = 16, nullable = false)
    private String audioMode = "auto";

    @Column(name = "transcription_provider_type", length = 32, nullable = false)
    private String transcriptionProviderType = "disabled";

    @Column(name = "transcription_provider_id", length = 64, nullable = false)
    private String transcriptionProviderId = "";

    @Column(name = "heartbeat_enabled", nullable = false)
    private boolean heartbeatEnabled = false;

    @Column(length = 16, nullable = false)
    private String heartbeatEvery = "6h";

    @Column(length = 64, nullable = false)
    private String heartbeatTarget = "main";

    @Column(name = "heartbeat_timeout_seconds", nullable = false)
    private int heartbeatTimeoutSeconds = 120;

    @Column(name = "user_timezone", length = 64, nullable = false)
    private String userTimezone = "UTC";

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

    public String getAudioMode() { return audioMode; }
    public void setAudioMode(String audioMode) { this.audioMode = audioMode; }

    public String getTranscriptionProviderType() { return transcriptionProviderType; }
    public void setTranscriptionProviderType(String v) { this.transcriptionProviderType = v; }
    public String getTranscriptionProviderId() { return transcriptionProviderId; }
    public void setTranscriptionProviderId(String v) { this.transcriptionProviderId = v; }

    public boolean isHeartbeatEnabled() { return heartbeatEnabled; }
    public void setHeartbeatEnabled(boolean v) { this.heartbeatEnabled = v; }
    public String getHeartbeatEvery() { return heartbeatEvery; }
    public void setHeartbeatEvery(String v) { this.heartbeatEvery = v; }
    public String getHeartbeatTarget() { return heartbeatTarget; }
    public void setHeartbeatTarget(String v) { this.heartbeatTarget = v; }
    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public void setHeartbeatTimeoutSeconds(int v) { this.heartbeatTimeoutSeconds = v; }

    public String getUserTimezone() { return userTimezone; }
    public void setUserTimezone(String v) { this.userTimezone = v; }
}
