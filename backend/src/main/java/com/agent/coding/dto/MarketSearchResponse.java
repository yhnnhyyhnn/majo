package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response for {@code GET /api/plugins/market/search}, matching the frontend
 * {@code MarketPluginListResponse} contract in {@code pluginMarket.ts}:
 * {@code {success, message, data: {total, plugins}}}.
 */
public class MarketSearchResponse {

    public static class Data {
        @JsonProperty("total")
        private int total;

        @JsonProperty("plugins")
        private List<MarketPluginEntry> plugins;

        public Data() {}
        public Data(int total, List<MarketPluginEntry> plugins) {
            this.total = total;
            this.plugins = plugins;
        }
        public int getTotal() { return total; }
        public void setTotal(int v) { this.total = v; }
        public List<MarketPluginEntry> getPlugins() { return plugins; }
        public void setPlugins(List<MarketPluginEntry> v) { this.plugins = v; }
    }

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("message")
    private String message;

    @JsonProperty("data")
    private Data data;

    public MarketSearchResponse() {}

    public MarketSearchResponse(boolean success, String message, Data data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static MarketSearchResponse ok(int total, List<MarketPluginEntry> plugins) {
        return new MarketSearchResponse(true, "", new Data(total, plugins));
    }

    public static MarketSearchResponse error(String message) {
        return new MarketSearchResponse(false, message, new Data(0, List.of()));
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { this.success = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public Data getData() { return data; }
    public void setData(Data v) { this.data = v; }
}
