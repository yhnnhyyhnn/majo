package com.agent.coding.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Minimal MCP (Model Context Protocol) client implementing just enough of
 * the JSON-RPC lifecycle to list tools from a connected MCP server.
 *
 * <p>Supported transports ( mirroring the original reference which uses the 
 * Python MCP SDK):</p>
 * <ul>
 *   <li>{@code stdio} — spawn the command and speak JSON-RPC over stdin/stdout;</li>
 *   <li>{@code streamable_http} — POST JSON-RPC to the endpoint URL;</li>
 *   <li>{@code sse} — discover the POST endpoint from the SSE stream, then POST.</li>
 * </ul>
 *
 * <p>Flow: {@code initialize} → {@code notifications/initialized} →
 * {@code tools/list}. Responses are matched by JSON-RPC id.</p>
 */
public class McpProtocolClient {

    private static final Logger log = LoggerFactory.getLogger(McpProtocolClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final int TIMEOUT_MS = 10_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Resolved endpoint description ready for a protocol call.
     */
    public record ClientEndpoint(
            String transport,
            String url,
            Map<String, String> headers,
            String command,
            List<String> args,
            Map<String, String> env,
            String cwd) {}

    /**
     * Connect and list tools. Each returned map has keys
     * {@code name}, {@code description}, {@code input_schema}.
     *
     * @throws Exception on any protocol / transport failure (caller maps to 502).
     */
    public List<Map<String, Object>> listTools(ClientEndpoint ep) throws Exception {
        List<Map<String, Object>> tools = new ArrayList<>();
        String transport = ep.transport() == null ? "stdio" : ep.transport();
        switch (transport) {
            case "stdio" -> listToolsStdio(ep, tools);
            case "sse" -> listToolsSse(ep, tools);
            default -> listToolsStreamableHttp(ep, tools);
        }
        return tools;
    }

    // ------------------------------------------------------------------
    // stdio transport
    // ------------------------------------------------------------------

    private void listToolsStdio(ClientEndpoint ep, List<Map<String, Object>> out) throws Exception {
        List<String> cmd = new ArrayList<>();
        if (ep.command() != null && !ep.command().isBlank()) cmd.add(ep.command().trim());
        cmd.addAll(ep.args() == null ? List.of() : ep.args());
        if (cmd.isEmpty()) {
            throw new IllegalArgumentException("MCP stdio client requires a command");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        if (ep.env() != null) {
            Map<String, String> merged = new LinkedHashMap<>(System.getenv());
            merged.putAll(ep.env());
            pb.environment().putAll(merged);
        }
        if (ep.cwd() != null && !ep.cwd().isBlank()) {
            pb.directory(new java.io.File(ep.cwd()));
        }
        Process process;
        try {
            process = pb.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to launch MCP server command: " + e.getMessage(), e);
        }

        // Drain stderr so the process never blocks on a full stderr pipe.
        Thread stderr = new Thread(() -> {
            try (BufferedReader err =
                         new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    log.debug("[mcp-stderr {}] {}", ep.command(), line);
                }
            } catch (Exception ignored) {
                // process died
            }
        });
        stderr.setDaemon(true);
        stderr.start();

        CompletableFuture<List<Map<String, Object>>> result = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 Writer outw = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {

                JsonNode initResp = sendRequest(in, outw, 1, "initialize", initParams());
                if (initResp == null) {
                    result.completeExceptionally(new IllegalStateException("No initialize response from MCP server"));
                    return;
                }
                sendNotification(outw, "notifications/initialized");
                JsonNode toolsResp = sendRequest(in, outw, 2, "tools/list", null);
                if (toolsResp == null) {
                    result.completeExceptionally(new IllegalStateException("No tools/list response from MCP server"));
                    return;
                }
                result.complete(parseTools(toolsResp));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        reader.setDaemon(true);
        reader.start();

        try {
            out.addAll(result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    private JsonNode sendRequest(BufferedReader in, Writer out, int id, String method, Map<String, Object> params)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        if (params != null) payload.put("params", params);
        out.write(MAPPER.writeValueAsString(payload));
        out.write("\n");
        out.flush();

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            JsonNode respId = node.get("id");
            if (respId != null && respId.asInt(-1) == id) {
                return node;
            }
            // Ignore server-initiated notifications (no id) and other ids.
        }
        return null;
    }

    private void sendNotification(Writer out, String method) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        out.write(MAPPER.writeValueAsString(payload));
        out.write("\n");
        out.flush();
    }

    // ------------------------------------------------------------------
    // streamable_http transport
    // ------------------------------------------------------------------

    private void listToolsStreamableHttp(ClientEndpoint ep, List<Map<String, Object>> out) throws Exception {
        if (ep.url() == null || ep.url().isBlank()) {
            throw new IllegalArgumentException("MCP streamable_http client requires a url");
        }
        URI uri = URI.create(ep.url());
        String initJson = MAPPER.writeValueAsString(
                jsonRpc(1, "initialize", initParams()));

        HttpRequest.Builder initReq = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        applyHeaders(initReq, ep.headers());
        initReq.POST(HttpRequest.BodyPublishers.ofString(initJson, StandardCharsets.UTF_8));

        HttpResponse<String> initResp = http.send(initReq.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode initBody = parseResponseBody(initResp);
        if (initBody == null || !initBody.hasNonNull("result")) {
            throw new IllegalStateException("MCP initialize failed: HTTP " + initResp.statusCode()
                    + (initBody == null ? "" : " " + initBody));
        }

        String toolsJson = MAPPER.writeValueAsString(jsonRpc(2, "tools/list", null));
        HttpRequest.Builder toolsReq = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        applyHeaders(toolsReq, ep.headers());
        toolsReq.POST(HttpRequest.BodyPublishers.ofString(toolsJson, StandardCharsets.UTF_8));

        HttpResponse<String> toolsResp = http.send(toolsReq.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode toolsBody = parseResponseBody(toolsResp);
        if (toolsBody == null) {
            throw new IllegalStateException("MCP tools/list failed: HTTP " + toolsResp.statusCode());
        }
        out.addAll(parseTools(toolsBody));
    }

    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            try {
                builder.header(e.getKey(), e.getValue() == null ? "" : e.getValue());
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping invalid MCP header '{}': {}", e.getKey(), ex.getMessage());
            }
        }
    }

    /** Parse a JSON or SSE response body into the JSON-RPC object. */
    private JsonNode parseResponseBody(HttpResponse<String> resp) throws Exception {
        String body = resp.body() == null ? "" : resp.body();
        String contentType = resp.headers().firstValue("content-type").orElse("");
        if (contentType.contains("text/event-stream") || body.startsWith("event:")) {
            for (String line : body.split("\r?\n")) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    try {
                        return MAPPER.readTree(data);
                    } catch (Exception e) {
                        // keep scanning; some events are meta (e.g. "endpoint")
                    }
                }
            }
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("MCP response is not JSON: {}", body.substring(0, Math.min(body.length(), 200)));
            return null;
        }
    }

    // ------------------------------------------------------------------
    // sse transport
    // ------------------------------------------------------------------

    private void listToolsSse(ClientEndpoint ep, List<Map<String, Object>> out) throws Exception {
        if (ep.url() == null || ep.url().isBlank()) {
            throw new IllegalArgumentException("MCP sse client requires a url");
        }
        // 1. Open the SSE stream and wait for the `endpoint` event.
        URI sseUri = URI.create(ep.url());
        HttpRequest.Builder sseReq = HttpRequest.newBuilder(sseUri)
                .timeout(Duration.ofSeconds(TIMEOUT_MS))
                .header("Accept", "text/event-stream");
        applyHeaders(sseReq, ep.headers());
        sseReq.GET();

        HttpResponse<String> sseResp = http.send(sseReq.build(), HttpResponse.BodyHandlers.ofString());
        String postUrl = null;
        for (String line : sseResp.body().split("\r?\n")) {
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                if (data.startsWith("http")) {
                    postUrl = data;
                    break;
                }
            }
        }
        if (postUrl == null) {
            throw new IllegalStateException("MCP SSE stream did not advertise an endpoint");
        }

        // 2. POST JSON-RPC to the discovered endpoint (same as streamable_http).
        ClientEndpoint httpEp = new ClientEndpoint(
                "streamable_http", postUrl, ep.headers(),
                null, List.of(), null, null);
        listToolsStreamableHttp(httpEp, out);
    }

    // ------------------------------------------------------------------
    // Protocol helpers
    // ------------------------------------------------------------------

    private Map<String, Object> initParams() {
        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", "majo");
        clientInfo.put("version", "1.0.0");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", clientInfo);
        return params;
    }

    private Map<String, Object> jsonRpc(int id, String method, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        if (params != null) payload.put("params", params);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTools(JsonNode resp) throws Exception {
        List<Map<String, Object>> tools = new ArrayList<>();
        JsonNode result = resp.get("result");
        if (result == null) {
            JsonNode error = resp.get("error");
            throw new IllegalStateException("MCP tools/list error: "
                    + (error == null ? "missing result" : error.toString()));
        }
        JsonNode arr = result.get("tools");
        if (arr == null || !arr.isArray()) return tools;
        for (JsonNode t : arr) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", t.path("name").asText(""));
            tool.put("description", t.path("description").asText(""));
            JsonNode schema = t.get("inputSchema");
            if (schema == null) schema = t.get("input_schema");
            if (schema != null && schema.isObject()) {
                tool.put("input_schema", MAPPER.convertValue(schema, Object.class));
            } else {
                tool.put("input_schema", Map.of());
            }
            tools.add(tool);
        }
        return tools;
    }
}
