package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Single marketplace plugin entry, matching the frontend
 * {@code MarketPluginEntry} contract in {@code pluginMarket.ts}.
 */
public class MarketPluginEntry {
    @JsonProperty("id")
    private String id;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("developer")
    private String developer;

    @JsonProperty("owner")
    private String owner;

    @JsonProperty("version")
    private String version;

    @JsonProperty("logo_url")
    private String logoUrl;

    @JsonProperty("downloads")
    private long downloads;

    @JsonProperty("view_count")
    private long viewCount;

    @JsonProperty("details_url")
    private String detailsUrl;

    /** locale code -> {description, category} */
    @JsonProperty("locales")
    private Map<String, Map<String, String>> locales;

    /** e.g. ["1.x", "2.x"] */
    @JsonProperty("qwenpaw_compat_labels")
    private List<String> qwenpawCompatLabels;

    @JsonProperty("is_featured")
    private boolean featured;

    public MarketPluginEntry() {}

    public MarketPluginEntry(String id, String displayName, String developer, String owner,
                             String version, String logoUrl, long downloads, long viewCount,
                             String detailsUrl, Map<String, Map<String, String>> locales,
                             List<String> qwenpawCompatLabels, boolean featured) {
        this.id = id;
        this.displayName = displayName;
        this.developer = developer;
        this.owner = owner;
        this.version = version;
        this.logoUrl = logoUrl;
        this.downloads = downloads;
        this.viewCount = viewCount;
        this.detailsUrl = detailsUrl;
        this.locales = locales;
        this.qwenpawCompatLabels = qwenpawCompatLabels;
        this.featured = featured;
    }

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getDeveloper() { return developer; }
    public void setDeveloper(String v) { this.developer = v; }
    public String getOwner() { return owner; }
    public void setOwner(String v) { this.owner = v; }
    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String v) { this.logoUrl = v; }
    public long getDownloads() { return downloads; }
    public void setDownloads(long v) { this.downloads = v; }
    public long getViewCount() { return viewCount; }
    public void setViewCount(long v) { this.viewCount = v; }
    public String getDetailsUrl() { return detailsUrl; }
    public void setDetailsUrl(String v) { this.detailsUrl = v; }
    public Map<String, Map<String, String>> getLocales() { return locales; }
    public void setLocales(Map<String, Map<String, String>> v) { this.locales = v; }
    public List<String> getQwenpawCompatLabels() { return qwenpawCompatLabels; }
    public void setQwenpawCompatLabels(List<String> v) { this.qwenpawCompatLabels = v; }
    public boolean isFeatured() { return featured; }
    public void setFeatured(boolean v) { this.featured = v; }
}
