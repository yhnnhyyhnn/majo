package com.agent.coding.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Web search with two interchangeable providers (qwenpaw #8200/#8204
 * counterpart):
 *
 * <ul>
 *   <li><b>Tavily</b> — requires {@code TAVILY_API_KEY} (the original
 *       contract, unchanged);</li>
 *   <li><b>Firecrawl</b> — {@code FIRECRAWL_API_KEY} optional; keyless
 *       search is allowed, and an authenticated 401 retries once without
 *       the key (upstream semantics).</li>
 * </ul>
 *
 * <p>Provider selection: {@code WEB_SEARCH_PROVIDER=tavily|firecrawl}
 * forces one; otherwise auto — Tavily when its key is set, Firecrawl
 * otherwise. Without any key the tool still tries keyless Firecrawl and
 * reports a readable error if the keyless budget is exhausted, so the
 * model can fall back to web_fetch.
 */
@Component
public class WebSearchTool {

    /** Overridable for tests; production endpoints. */
    static volatile String tavilyUrl = "https://api.tavily.com/search";
    static volatile String firecrawlUrl = "https://api.firecrawl.dev/v2/search";
    /** Test hook: overrides System.getenv lookups when non-null. */
    static volatile Map<String, String> envOverride;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static String env(String name) {
        Map<String, String> override = envOverride;
        if (override != null && override.containsKey(name)) {
            String v = override.get(name);
            return v == null ? "" : v;
        }
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }

    enum Provider { TAVILY, FIRECRAWL }

    /** Explicit WEB_SEARCH_PROVIDER wins; auto falls back across keys. */
    static Provider selectProvider() {
        String forced = env("WEB_SEARCH_PROVIDER").toLowerCase();
        if ("tavily".equals(forced)) return Provider.TAVILY;
        if ("firecrawl".equals(forced)) return Provider.FIRECRAWL;
        if (!env("TAVILY_API_KEY").isBlank()) return Provider.TAVILY;
        return Provider.FIRECRAWL;
    }

    @Tool(name = "web_search", description = "搜索互联网获取实时信息(支持 Tavily 与 Firecrawl,经环境变量 TAVILY_API_KEY / FIRECRAWL_API_KEY 配置)")
    public String webSearch(
        @ToolParam(name = "query", description = "搜索关键词") String query,
        @ToolParam(name = "max_results", description = "返回结果数量(可选，默认5)") Integer maxResults
    ) {
        if (query == null || query.isBlank()) {
            return "错误: query 不能为空";
        }
        int max = maxResults != null ? Math.min(Math.max(maxResults, 1), 10) : 5;
        return selectProvider() == Provider.TAVILY
                ? searchTavily(query, max)
                : searchFirecrawl(query, max);
    }

    private String searchTavily(String query, int max) {
        String apiKey = env("TAVILY_API_KEY");
        if (apiKey.isBlank()) {
            return "[不可用] 未配置 TAVILY_API_KEY(已强制 Tavily),无法执行 web_search。"
                    + "可改用 FIRECRAWL_API_KEY 或移除 WEB_SEARCH_PROVIDER 以自动选择,或用 web_fetch 抓取已知 URL。";
        }
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "api_key", apiKey,
                    "query", query,
                    "max_results", max,
                    "search_depth", "basic"));
            HttpResponse<String> resp = post(tavilyUrl, payload, null);
            if (resp.statusCode() >= 400) {
                return "搜索失败: Tavily HTTP " + resp.statusCode() + ": " + resp.body();
            }
            JsonNode results = MAPPER.readTree(resp.body()).get("results");
            if (results == null || !results.isArray() || results.isEmpty()) {
                return "未找到与 '" + query + "' 相关的结果。";
            }
            StringBuilder sb = new StringBuilder("搜索 '" + query + "' 的结果:\n");
            int i = 1;
            for (JsonNode r : results) {
                if (i > max) break;
                sb.append("[").append(i).append("] ")
                        .append(r.path("title").asText("(无标题)")).append("\n")
                        .append(r.path("url").asText("")).append("\n")
                        .append(r.path("content").asText("")).append("\n\n");
                i++;
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    private String searchFirecrawl(String query, int max) {
        String apiKey = env("FIRECRAWL_API_KEY");
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "query", query,
                    "limit", max,
                    "sources", java.util.List.of("web")));
            HttpResponse<String> resp = post(firecrawlUrl, payload, apiKey);
            // Keyless search may work: an authenticated 401 retries once
            // without the key (qwenpaw firecrawl semantics).
            if (resp.statusCode() == 401 && !apiKey.isBlank()) {
                resp = post(firecrawlUrl, payload, "");
            }
            if (resp.statusCode() >= 400) {
                return "搜索失败: Firecrawl HTTP " + resp.statusCode() + ": " + resp.body()
                        + (apiKey.isBlank()
                            ? "(无 key 额度可能已用尽;可配置 FIRECRAWL_API_KEY)"
                            : "");
            }
            JsonNode items = MAPPER.readTree(resp.body()).path("data").path("web");
            if (!items.isArray() || items.isEmpty()) {
                return "未找到与 '" + query + "' 相关的结果。";
            }
            StringBuilder sb = new StringBuilder("搜索 '" + query + "' 的结果:\n");
            int i = 1;
            for (JsonNode r : items) {
                if (i > max) break;
                String snippet = r.has("description") && !r.path("description").isNull()
                        ? r.path("description").asText("") : r.path("markdown").asText("");
                sb.append("[").append(i).append("] ")
                        .append(r.path("title").asText("(无标题)")).append("\n")
                        .append(r.path("url").asText("")).append("\n")
                        .append(snippet).append("\n\n");
                i++;
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    private static HttpResponse<String> post(String url, String jsonPayload, String bearerKey)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));
        if (bearerKey != null && !bearerKey.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerKey);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
