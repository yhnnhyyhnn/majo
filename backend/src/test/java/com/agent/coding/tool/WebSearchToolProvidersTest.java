package com.agent.coding.tool;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dual-provider web_search (qwenpaw #8200/#8204 counterpart): provider
 * selection by explicit env or key auto-fallback, Tavily contract
 * unchanged, Firecrawl with optional key + 401 keyless retry. A local
 * HttpServer stands in for both provider APIs.
 */
class WebSearchToolProvidersTest {

    private HttpServer server;
    private final AtomicInteger tavilyHits = new AtomicInteger();
    private final AtomicInteger firecrawlHits = new AtomicInteger();
    private final AtomicReference<String> lastFirecrawlAuth = new AtomicReference<>("(none)");
    private final AtomicReference<String> lastFirecrawlBody = new AtomicReference<>("(none)");
    private volatile int firecrawlStatus = 200;
    private volatile String firecrawlResponse = "{\"data\":{\"web\":[" +
            "{\"title\":\"结果一\",\"url\":\"https://a.example/1\",\"description\":\"说明 A\"}," +
            "{\"title\":\"结果二\",\"url\":\"https://a.example/2\",\"description\":\"说明 B\"}]}}";
    private volatile int tavilyStatus = 200;
    private volatile String tavilyResponse = "{\"results\":[" +
            "{\"title\":\"T 结果\",\"url\":\"https://t.example/1\",\"content\":\"T 内容\"}]}}";

    @BeforeEach
    void startFakeProviders() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/tavily/search", exchange -> {
            tavilyHits.incrementAndGet();
            byte[] out = tavilyResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(tavilyStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.createContext("/v2/search", exchange -> {
            firecrawlHits.incrementAndGet();
            lastFirecrawlAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastFirecrawlBody.set(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = firecrawlResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(firecrawlStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        WebSearchTool.tavilyUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/tavily/search";
        WebSearchTool.firecrawlUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/search";
    }

    @AfterEach
    void stopAndReset() {
        server.stop(0);
        WebSearchTool.tavilyUrl = "https://api.tavily.com/search";
        WebSearchTool.firecrawlUrl = "https://api.firecrawl.dev/v2/search";
        WebSearchTool.envOverride = null;
    }

    private static void env(Map.Entry<String, String>... pairs) {
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, String> e : pairs) m.put(e.getKey(), e.getValue());
        WebSearchTool.envOverride = m;
    }

    @Test
    void explicitTavilyKeepsOriginalContract() {
        env(Map.entry("WEB_SEARCH_PROVIDER", "tavily"),
            Map.entry("TAVILY_API_KEY", "tvly-key"));

        String out = new WebSearchTool().webSearch("majo test", 2);

        assertTrue(out.contains("T 结果"), out);
        assertEquals(1, tavilyHits.get());
        assertEquals(0, firecrawlHits.get());
    }

    @Test
    void forcedTavilyWithoutKeyNamesTheAlternative() {
        env(Map.entry("WEB_SEARCH_PROVIDER", "tavily"));

        String out = new WebSearchTool().webSearch("majo test", null);

        assertTrue(out.contains("TAVILY_API_KEY"), out);
        assertTrue(out.contains("FIRECRAWL_API_KEY"), out);
    }

    @Test
    void autoPrefersTavilyWhenItsKeyExists() {
        env(Map.entry("TAVILY_API_KEY", "tvly-key"),
            Map.entry("FIRECRAWL_API_KEY", "fc-key"));

        new WebSearchTool().webSearch("majo test", null);

        assertEquals(1, tavilyHits.get());
        assertEquals(0, firecrawlHits.get());
    }

    @Test
    void autoFallsBackToFirecrawlAndSendsKeyAsBearer() {
        env(Map.entry("FIRECRAWL_API_KEY", "fc-key"));

        String out = new WebSearchTool().webSearch("majo test", 2);

        assertTrue(out.contains("结果一") && out.contains("说明 A"), out);
        assertEquals("Bearer fc-key", lastFirecrawlAuth.get());
        assertTrue(lastFirecrawlBody.get().contains("\"sources\":[\"web\"]"),
                lastFirecrawlBody.get());
    }

    @Test
    void firecrawlAuthenticated401RetriesKeylessOnce() {
        env(Map.entry("FIRECRAWL_API_KEY", "fc-key"));
        firecrawlStatus = 401;
        firecrawlResponse = "{\"error\":\"bad key\"}";

        String out = new WebSearchTool().webSearch("majo test", 2);

        assertEquals(2, firecrawlHits.get(), "one authenticated + one keyless retry");
        assertNull(lastFirecrawlAuth.get(), "retry goes out keyless");
        assertTrue(out.contains("Firecrawl HTTP 401"), out);
    }

    @Test
    void keylessFirecrawlWorksWhenAllowed() {
        env();

        String out = new WebSearchTool().webSearch("majo test", 2);

        assertTrue(out.contains("结果二"), out);
        assertNull(lastFirecrawlAuth.get(), "keyless request carries no Authorization header");
    }
}
