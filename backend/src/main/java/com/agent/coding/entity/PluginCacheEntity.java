package com.agent.coding.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "plugin_cache")
public class PluginCacheEntity {
    @Id
    @Column(length = 128)
    private String id;

    @Column(length = 16, nullable = false)
    private String source;

    @Column(name = "plugin_id", length = 128, nullable = false)
    private String pluginId;

    @Column(length = 255)
    private String name;

    @Column(columnDefinition = "CLOB")
    private String description;

    @Column(length = 32)
    private String version;

    @Column(length = 255)
    private String author;

    @Column(length = 32)
    private String kind;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "display_size", length = 32)
    private String displaySize;

    @Column(name = "install_url", length = 512)
    private String installUrl;

    @Column(length = 128)
    private String sha256;

    @Column(length = 64)
    private String category;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(length = 255)
    private String developer;

    @Column(length = 128)
    private String owner;

    @Column(name = "logo_url", length = 512)
    private String logoUrl;

    @Column
    private Long downloads;

    @Column(name = "view_count")
    private Long viewCount;

    @Column(name = "details_url", length = 512)
    private String detailsUrl;

    @Column(name = "is_featured")
    private Boolean featured;

    @Column(name = "compat_labels", columnDefinition = "CLOB")
    private String compatLabels;

    @Column(columnDefinition = "CLOB")
    private String locales;

    @Column(name = "sort_rank")
    private Integer sortRank;

    @Column(name = "cached_at", nullable = false)
    private LocalDateTime cachedAt = LocalDateTime.now();

    public PluginCacheEntity() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getPluginId() { return pluginId; }
    public void setPluginId(String v) { this.pluginId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }
    public String getAuthor() { return author; }
    public void setAuthor(String v) { this.author = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long v) { this.sizeBytes = v; }
    public String getDisplaySize() { return displaySize; }
    public void setDisplaySize(String v) { this.displaySize = v; }
    public String getInstallUrl() { return installUrl; }
    public void setInstallUrl(String v) { this.installUrl = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getDeveloper() { return developer; }
    public void setDeveloper(String v) { this.developer = v; }
    public String getOwner() { return owner; }
    public void setOwner(String v) { this.owner = v; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String v) { this.logoUrl = v; }
    public Long getDownloads() { return downloads; }
    public void setDownloads(Long v) { this.downloads = v; }
    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long v) { this.viewCount = v; }
    public String getDetailsUrl() { return detailsUrl; }
    public void setDetailsUrl(String v) { this.detailsUrl = v; }
    public Boolean getFeatured() { return featured; }
    public void setFeatured(Boolean v) { this.featured = v; }
    public String getCompatLabels() { return compatLabels; }
    public void setCompatLabels(String v) { this.compatLabels = v; }
    public String getLocales() { return locales; }
    public void setLocales(String v) { this.locales = v; }
    public Integer getSortRank() { return sortRank; }
    public void setSortRank(Integer v) { this.sortRank = v; }
    public LocalDateTime getCachedAt() { return cachedAt; }
    public void setCachedAt(LocalDateTime v) { this.cachedAt = v; }
}
