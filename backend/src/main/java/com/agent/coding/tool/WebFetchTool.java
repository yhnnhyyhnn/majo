package com.agent.coding.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fetch a URL and extract readable text. HTML is stripped with regex
 * (no extra dependency); binary content is detected by content-type and
 * returned as a short notice rather than garbage.
 */
@Component
public class WebFetchTool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final int MAX_BODY = 300_000;
    private static final int MAX_TEXT = 20_000;

    @Tool(name = "web_fetch", description = "抓取指定 URL 的内容并提取可读文本")
    public String webFetch(
        @ToolParam(name = "url", description = "要抓取的 URL") String url
    ) {
        if (url == null || url.isBlank()) {
            return "错误: url 不能为空";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "错误: 仅支持 http/https 协议";
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Majo/0.1 coding agent")
                    .header("Accept", "text/html,text/plain,application/json,*/*")
                    .GET()
                    .build();
            var resp = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            if (status >= 400) {
                return "抓取失败: HTTP " + status + " (" + url + ")";
            }
            String body;
            try (var in = resp.body()) {
                byte[] bytes = in.readNBytes(MAX_BODY + 1);
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            if (contentType.contains("application/json")) {
                return "[HTTP " + status + "] " + url + "\n" + truncate(body.trim());
            }
            if (!contentType.contains("html") && !contentType.contains("text")) {
                return "[HTTP " + status + "] " + url + "\n[二进制内容, Content-Type: "
                        + contentType + "，不展示]";
            }
            String text = htmlToText(body);
            return "[HTTP " + status + "] " + url + "\n" + truncate(text);
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_TEXT) {
            return s;
        }
        return s.substring(0, MAX_TEXT) + "\n...[内容过长已截断]";
    }

    /** Crude but dependency-free HTML → text. */
    static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?s)<!--.*?-->", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)<(p|div|h[1-6]|li|tr|pre|blockquote|section|article)[^>]*>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr|pre|blockquote|section|article)>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("(?m)^[ \\t]+|[ \\t]+$", "");
        return s.replaceAll("\\n{3,}", "\n\n").trim();
    }
}
