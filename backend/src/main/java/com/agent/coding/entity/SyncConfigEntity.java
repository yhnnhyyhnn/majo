package com.agent.coding.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "sync_configs")
public class SyncConfigEntity {
    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    @Column(length = 512, nullable = false)
    private String url;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "synced_count", nullable = false)
    private int syncedCount;

    @Column(name = "sync_status", length = 16, nullable = false)
    private String syncStatus = "pending";

    public SyncConfigEntity() {}
    public SyncConfigEntity(String key, String url) { this.configKey = key; this.url = url; }

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String v) { this.configKey = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }
    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(LocalDateTime v) { this.lastSyncedAt = v; }
    public int getSyncedCount() { return syncedCount; }
    public void setSyncedCount(int v) { this.syncedCount = v; }
    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String v) { this.syncStatus = v; }
}
