package com.agent.coding.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight session-based browser (no Playwright): opens pages, extracts
 * text + links, supports click (follow a link by index) and back. Good for
 * simple multi-page browsing; JavaScript rendering is not supported.
 */
@Component
public class BrowserUseTool {

    private static final Logger log = LoggerFactory.getLogger(BrowserUseTool.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final Pattern LINK_RE = Pattern.compile(
            "<a[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_STRIP = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_TEXT = 12_000;
    private static final int MAX_LINKS = 30;

    /** Single shared browsing session (one tab). */
    private static final Deque<String> HISTORY = new ArrayDeque<>();
    private static volatile String currentUrl;
    private static volatile String currentTitle = "";
    private static volatile List<String[]> currentLinks = List.of(); // [href, text]

    @Tool(name = "browser_use", description = "会话式网页浏览: open/links/back/click")
    public String browserUse(
        @ToolParam(name = "url", description = "URL(open/click 时)") String url,
        @ToolParam(name = "action", description = "动作: open/links/back/click(默认 open)") String action
    ) {
        String act = action == null || action.isBlank() ? "open" : action.trim().toLowerCase();
        return switch (act) {
            case "open" -> open(url);
            case "links" -> listLinks();
            case "back" -> back();
            case "click" -> click(url);
            default -> "错误: 不支持的 action '" + act + "' (支持 open/links/back/click)";
        };
    }

    private synchronized String open(String url) {
        if (url == null || url.isBlank()) {
            return "错误: open 需要 url";
        }
        String resolved = url.startsWith("http://") || url.startsWith("https://") ? url : "https://" + url;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(resolved))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Majo/0.1 coding agent")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                return "打开失败: HTTP " + resp.statusCode() + " (" + resolved + ")";
            }
            String html = resp.body();
            if (currentUrl != null) {
                HISTORY.addLast(currentUrl);
            }
            currentUrl = resolved;
            currentTitle = extractTitle(html);
            currentLinks = extractLinks(html, resolved);
            return renderPage(currentTitle, html, true);
        } catch (Exception e) {
            return "打开失败: " + e.getMessage();
        }
    }

    private String listLinks() {
        if (currentUrl == null) {
            return "尚未打开任何页面，请先用 browser_use open <url>";
        }
        StringBuilder sb = new StringBuilder("当前页面: " + currentTitle + " (" + currentUrl + ")\n链接:\n");
        if (currentLinks.isEmpty()) {
            sb.append("(无链接)");
            return sb.toString();
        }
        for (int i = 0; i < currentLinks.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
              .append(currentLinks.get(i)[1]).append(" → ").append(currentLinks.get(i)[0]).append("\n");
        }
        return sb.toString().trim();
    }

    private String back() {
        if (HISTORY.isEmpty()) {
            return "没有历史记录";
        }
        String prev = HISTORY.removeLast();
        return open(prev);
    }

    private String click(String index) {
        if (index == null || index.isBlank()) {
            return "错误: click 需要链接序号(用 links 查看)";
        }
        int i;
        try {
            i = Integer.parseInt(index.trim());
        } catch (NumberFormatException e) {
            return "错误: 链接序号必须是数字";
        }
        if (i < 1 || i > currentLinks.size()) {
            return "错误: 链接序号超出范围 (1-" + currentLinks.size() + ")";
        }
        String href = currentLinks.get(i - 1)[0];
        return open(href);
    }

    // ── parsing helpers ──────────────────────────────────────────────

    private static String extractTitle(String html) {
        Matcher m = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(html);
        if (m.find()) {
            return WHITESPACE.matcher(TAG_STRIP.matcher(m.group(1)).replaceAll(" ")).replaceAll(" ").trim();
        }
        return "(无标题)";
    }

    private static List<String[]> extractLinks(String html, String base) {
        List<String[]> links = new ArrayList<>();
        Matcher m = LINK_RE.matcher(html);
        while (m.find() && links.size() < MAX_LINKS) {
            String href = m.group(1).trim();
            String text = WHITESPACE.matcher(TAG_STRIP.matcher(m.group(2)).replaceAll(" "))
                    .replaceAll(" ").trim();
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")) {
                continue;
            }
            if (!href.startsWith("http://") && !href.startsWith("https://")) {
                href = base.endsWith("/") ? base + href : base + "/" + href;
            }
            if (text.isEmpty()) {
                text = href;
            }
            links.add(new String[]{href, text.length() > 60 ? text.substring(0, 60) : text});
        }
        return links;
    }

    private static String renderPage(String title, String html, boolean withLinks) {
        String body = WebFetchTool.htmlToText(html);
        if (body.length() > MAX_TEXT) {
            body = body.substring(0, MAX_TEXT) + "\n...[内容过长已截断]";
        }
        StringBuilder sb = new StringBuilder("页面: ").append(title)
                .append("\nURL: ").append(currentUrl).append("\n\n").append(body);
        if (withLinks && !currentLinks.isEmpty()) {
            sb.append("\n\n链接数: ").append(currentLinks.size())
              .append(" (用 browser_use links 查看，click <序号> 打开)");
        }
        return sb.toString();
    }
}
