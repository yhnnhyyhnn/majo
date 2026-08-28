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
 * Web search via Tavily. Requires the {@code TAVILY_API_KEY} environment
 * variable (same contract as QwenPaw). Without a key, returns an explicit
 * "not configured" notice so the model falls back to web_fetch.
 */
@Component
public class WebSearchTool {

    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Tool(name = "web_search", description = "搜索互联网获取实时信息（需配置 TAVILY_API_KEY）")
    public String webSearch(
        @ToolParam(name = "query", description = "搜索关键词") String query,
        @ToolParam(name = "max_results", description = "返回结果数量(可选，默认5)") Integer maxResults
    ) {
        if (query == null || query.isBlank()) {
            return "错误: query 不能为空";
        }
        String apiKey = System.getenv("TAVILY_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return "[不可用] 未配置 TAVILY_API_KEY，无法执行 web_search。"
                    + "请改用 web_fetch 直接抓取已知 URL。";
        }
        try {
            int max = maxResults != null ? Math.min(Math.max(maxResults, 1), 10) : 5;
            String payload = MAPPER.writeValueAsString(Map.of(
                    "api_key", apiKey,
                    "query", query,
                    "max_results", max,
                    "search_depth", "basic"));
            var request = HttpRequest.newBuilder(URI.create(TAVILY_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            var resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                return "搜索失败: Tavily HTTP " + resp.statusCode() + ": " + resp.body();
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode results = root.get("results");
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
}
