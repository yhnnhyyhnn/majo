package com.agent.coding.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.agent.coding.mcp.McpModels.CREDENTIAL_ALIAS_OAUTH;
import static com.agent.coding.mcp.McpModels.CREDENTIAL_KIND_OAUTH_AUTH_CODE;

/**
 * OAuth 2.1 authorization flows for remote MCP clients.
 *
 * <p>Implements RFC 8414 (Authorization Server Metadata), RFC 9728
 * (Protected Resource Metadata), RFC 7591 (Dynamic Client Registration) and
 * PKCE (RFC 7636) for interactive browser-based OAuth flows triggered from
 * the frontend. Port of qwenpaw/app/routers/mcp_oauth.py.</p>
 */
@Service
public class McpOAuthService {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TTL_SECONDS = 600;
    private static final String AGENT_ID = "default";
    private static final String OAUTH_CALLBACK_PATH = "/api/mcp/oauth/callback";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** In-memory state store: state_token -> OAuthSession (TTL 10 min). */
    private final Map<String, OAuthSession> stateStore = new ConcurrentHashMap<>();

    private record OAuthSession(
            String clientKey,
            String codeVerifier,
            String clientId,
            String authEndpoint,
            String tokenEndpoint,
            String redirectUri,
            String scope,
            long createdAtNanos) {
        boolean isExpired() {
            return System.nanoTime() - createdAtNanos > TTL_SECONDS * 1_000_000_000L;
        }
    }

    // ------------------------------------------------------------------
    // PKCE helpers (RFC 7636)
    // ------------------------------------------------------------------

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String codeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    // ------------------------------------------------------------------
    // Metadata discovery helpers
    // ------------------------------------------------------------------

    /** Fetch JSON from url; return null on any error. */
    private Map<String, Object> fetchJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return asMap(MAPPER.readValue(resp.body(), Object.class));
            }
        } catch (Exception e) {
            log.debug("OAuth metadata fetch failed for {}: {}", url, e.getMessage());
        }
        return null;
    }

    /** Return resource_metadata URL from 401 WWW-Authenticate header. */
    private String probeResourceMetadataUrl(String mcpUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(mcpUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 401) return null;
            String wwwAuth = resp.headers().firstValue("www-authenticate").orElse("");
            for (String part : wwwAuth.split(",")) {
                if (part.toLowerCase().contains("resource_metadata=")) {
                    return part.split("=", 2)[1].trim().replace("\"", "");
                }
            }
        } catch (Exception e) {
            log.debug("MCP probe failed for {}: {}", mcpUrl, e.getMessage());
        }
        return null;
    }

    /** Return the authorization-server URL from PRM discovery (RFC 9728). */
    private String resolveAuthServerUrl(String mcpUrl) {
        // Try 401 header first
        String rmUrl = probeResourceMetadataUrl(mcpUrl);
        if (rmUrl != null) {
            Map<String, Object> prm = fetchJson(rmUrl);
            Object servers = prm == null ? null : prm.get("authorization_servers");
            if (servers instanceof java.util.List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
        }
        // Fall back to well-known PRM paths
        URI parsed = URI.create(mcpUrl.endsWith("/") ? mcpUrl : mcpUrl + "/");
        String root = parsed.getScheme() + "://" + parsed.getAuthority();
        String path = parsed.getPath().replaceAll("^/", "").replaceAll("/$", "");
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (!path.isEmpty()) {
            candidates.add(root + "/.well-known/oauth-protected-resource/" + path);
        }
        candidates.add(root + "/.well-known/oauth-protected-resource");
        for (String url : candidates) {
            Map<String, Object> prm = fetchJson(url);
            Object servers = prm == null ? null : prm.get("authorization_servers");
            if (servers instanceof java.util.List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
        }
        return null;
    }

    /** Fetch authorization-server metadata via RFC 8414 / OIDC discovery. */
    private Map<String, Object> fetchAsMetadata(String authServerUrl) {
        URI parsed = URI.create(authServerUrl.endsWith("/") ? authServerUrl : authServerUrl + "/");
        String asRoot = parsed.getScheme() + "://" + parsed.getAuthority();
        String asPath = parsed.getPath().replaceAll("^/", "").replaceAll("/$", "");
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (!asPath.isEmpty()) {
            candidates.add(asRoot + "/.well-known/oauth-authorization-server/" + asPath);
            candidates.add(asRoot + "/.well-known/openid-configuration/" + asPath);
            candidates.add(authServerUrl + "/.well-known/openid-configuration");
        } else {
            candidates.add(asRoot + "/.well-known/oauth-authorization-server");
            candidates.add(asRoot + "/.well-known/openid-configuration");
        }
        for (String url : candidates) {
            Map<String, Object> meta = fetchJson(url);
            if (meta != null && meta.containsKey("authorization_endpoint")) {
                return meta;
            }
        }
        return null;
    }

    /**
     * Discover OAuth endpoints via RFC 9728 + RFC 8414 / OIDC discovery.
     * Returns (authorization_endpoint, token_endpoint, registration_endpoint|null).
     */
    private String[] discoverOauthMetadata(String mcpUrl) {
        String authServerUrl = resolveAuthServerUrl(mcpUrl);
        if (authServerUrl == null) {
            throw new McpException(400,
                    "Could not discover OAuth authorization server for this MCP endpoint. "
                            + "The server may not expose Protected Resource Metadata (RFC 9728). "
                            + "Please enter auth_endpoint and token_endpoint manually.");
        }
        Map<String, Object> asMeta = fetchAsMetadata(authServerUrl);
        if (asMeta == null || !asMeta.containsKey("authorization_endpoint")) {
            throw new McpException(400,
                    "Could not retrieve authorization server metadata from " + authServerUrl + ". "
                            + "Please enter auth_endpoint and token_endpoint manually.");
        }
        if (!asMeta.containsKey("token_endpoint")) {
            throw new McpException(400,
                    "Authorization server metadata from " + authServerUrl
                            + " is missing 'token_endpoint'. "
                            + "Please enter auth_endpoint and token_endpoint manually.");
        }
        return new String[]{
                String.valueOf(asMeta.get("authorization_endpoint")),
                String.valueOf(asMeta.get("token_endpoint")),
                asMeta.containsKey("registration_endpoint")
                        ? String.valueOf(asMeta.get("registration_endpoint")) : null,
        };
    }

    /** Attempt Dynamic Client Registration (RFC 7591); return client_id. */
    private String dynamicRegister(String registrationEndpoint, String redirectUri) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", "Majo MCP Client");
        payload.put("redirect_uris", java.util.List.of(redirectUri));
        payload.put("grant_types", java.util.List.of("authorization_code", "refresh_token"));
        payload.put("response_types", java.util.List.of("code"));
        payload.put("token_endpoint_auth_method", "none");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(registrationEndpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                Map<String, Object> parsed = asMap(MAPPER.readValue(resp.body(), Object.class));
                Object clientId = parsed.get("client_id");
                return clientId == null ? null : String.valueOf(clientId);
            }
        } catch (Exception e) {
            log.debug("Dynamic client registration failed: {}", e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Main flow: start
    // ------------------------------------------------------------------

    /**
     * Start an interactive OAuth 2.1 PKCE flow for an MCP client.
     * Returns the authorization URL for the frontend popup.
     */
    public McpModels.McpOAuthStartResponse startOAuth(
            String clientKey,
            McpModels.McpOAuthStartRequest body,
            String redirectUri) {
        purgeExpired();

        Map<String, Object> card = McpStore.loadCard(clientKey);
        String endpointUrl = endpointUrlOf(card, body);
        if (endpointUrl.isEmpty()) {
            throw new McpException(400, "OAuth MCP client must have a remote URL.");
        }

        // Resolve endpoints
        String authEndpoint;
        String tokenEndpoint;
        String registrationEndpoint;
        boolean hasOverride = body != null && notBlank(body.authEndpoint()) && notBlank(body.tokenEndpoint());
        if (hasOverride) {
            authEndpoint = body.authEndpoint();
            tokenEndpoint = body.tokenEndpoint();
            registrationEndpoint = null;
        } else {
            String[] discovered = discoverOauthMetadata(endpointUrl);
            authEndpoint = discovered[0];
            tokenEndpoint = discovered[1];
            registrationEndpoint = discovered[2];
        }

        // Resolve client_id
        String clientId = body == null ? "" : (body.clientId() == null ? "" : body.clientId());
        Map<String, Object> existingOauth = McpStore.loadCredentialOrNull(
                McpStore.mcpOauthCredentialRef(clientKey));
        if (clientId.isEmpty() && existingOauth != null) {
            Map<String, Object> publicMap = asMap(existingOauth.get("public"));
            clientId = str(publicMap.get("client_id"));
        }
        if (clientId.isEmpty() && registrationEndpoint != null) {
            String registered = dynamicRegister(registrationEndpoint, redirectUri);
            if (registered != null) clientId = registered;
        }

        // PKCE
        String verifier = generateCodeVerifier();
        String challenge;
        try {
            challenge = codeChallenge(verifier);
        } catch (Exception e) {
            throw new McpException(500, "Failed to generate PKCE challenge");
        }
        byte[] stateBytes = new byte[16];
        RANDOM.nextBytes(stateBytes);
        String state = java.util.HexFormat.of().formatHex(stateBytes);

        stateStore.put(state, new OAuthSession(
                clientKey, verifier, clientId, authEndpoint, tokenEndpoint,
                redirectUri, body == null ? "" : (body.scope() == null ? "" : body.scope()),
                System.nanoTime()));

        // Build authorization URL
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("state", state);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        if (!clientId.isEmpty()) params.put("client_id", clientId);
        if (body != null && notBlank(body.scope())) params.put("scope", body.scope());

        String authUrl = authEndpoint + "?" + urlEncode(params);
        return new McpModels.McpOAuthStartResponse(authUrl, state);
    }

    private String endpointUrlOf(Map<String, Object> card, McpModels.McpOAuthStartRequest body) {
        Map<String, Object> endpoint = asMap(card.get("endpoint"));
        String url = str(endpoint.get("url"));
        if (url.isEmpty() && body != null) url = str(body.url());
        return url;
    }

    // ------------------------------------------------------------------
    // Main flow: callback
    // ------------------------------------------------------------------

    /**
     * Handle the OAuth 2.1 authorization code callback; returns the HTML
     * popup page that notifies the opener window.
     */
    public String oauthCallback(String code, String state, String error, String errorDescription) {
        purgeExpired();

        if (notBlank(error)) {
            return makeErrorPage(notBlank(errorDescription) ? errorDescription : error);
        }
        if (!notBlank(code) || !notBlank(state)) {
            return makeErrorPage("Missing 'code' or 'state' parameter.");
        }

        OAuthSession session = stateStore.get(state);
        if (session == null || session.isExpired()) {
            return makeErrorPage("OAuth session expired or not found. Please try again.");
        }

        try {
            Map<String, Object> tokens = exchangeCodeForTokens(session, code);
            persistTokens(session, tokens);
        } catch (Exception exc) {
            log.error("OAuth callback failed for '{}': {}", session.clientKey(), exc.getMessage());
            return makeErrorPage(String.valueOf(
                    exc instanceof McpException m ? m.getMessage() : exc.getMessage()));
        }

        stateStore.remove(state);

        String successBody =
                "<p style='color:#27ae60;font-size:1.8em;margin:0'>&#10003;</p>"
                        + "<p style='font-size:1.1em;font-weight:600;margin:8px 0 4px'>"
                        + "Authorization successful!</p>"
                        + "<p style='color:#888;font-size:13px'>"
                        + "This window will close shortly.</p>";
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("clientKey", session.clientKey());
        extra.put("agentId", AGENT_ID);
        return popupHtml("success", successBody, extra);
    }

    private Map<String, Object> exchangeCodeForTokens(OAuthSession session, String code) {
        Map<String, String> tokenData = new LinkedHashMap<>();
        tokenData.put("grant_type", "authorization_code");
        tokenData.put("code", code);
        tokenData.put("redirect_uri", session.redirectUri());
        tokenData.put("code_verifier", session.codeVerifier());
        if (!session.clientId().isEmpty()) tokenData.put("client_id", session.clientId());

        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(session.tokenEndpoint()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(urlEncode(tokenData), StandardCharsets.UTF_8))
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception exc) {
            throw new McpException(500, "Token exchange request failed: " + exc.getMessage());
        }
        if (resp.statusCode() != 200 && resp.statusCode() != 201) {
            throw new McpException(500,
                    "Token exchange failed (HTTP " + resp.statusCode() + "): "
                            + truncate(resp.body(), 300));
        }
        try {
            return asMap(MAPPER.readValue(resp.body(), Object.class));
        } catch (Exception exc) {
            throw new McpException(500, "Invalid token response: " + exc.getMessage());
        }
    }

    private void persistTokens(OAuthSession session, Map<String, Object> tokens) {
        String accessToken = str(tokens.get("access_token"));
        if (accessToken.isEmpty()) {
            throw new McpException(500, "Token response did not contain an access_token.");
        }
        String refreshToken = str(tokens.get("refresh_token"));
        int expiresIn;
        try {
            expiresIn = (int) Double.parseDouble(str(tokens.get("expires_in")));
        } catch (Exception e) {
            expiresIn = 3600;
        }
        if (expiresIn <= 0) expiresIn = 3600;
        double expiresAt = (System.currentTimeMillis() / 1000.0) + expiresIn;
        String scope = str(tokens.get("scope"));
        if (scope.isEmpty()) scope = session.scope();

        String oauthRef = McpStore.mcpOauthCredentialRef(session.clientKey());
        Map<String, Object> existing = McpStore.loadCredentialOrNull(oauthRef);
        Map<String, Object> publicMap = new LinkedHashMap<>();
        if (existing != null) publicMap.putAll(asMap(existing.get("public")));
        publicMap.put("client_id", session.clientId().isEmpty()
                ? str(publicMap.get("client_id")) : session.clientId());
        publicMap.put("scope", scope);
        publicMap.put("expires_at", expiresAt);
        publicMap.put("token_endpoint", session.tokenEndpoint());
        publicMap.put("auth_endpoint", session.authEndpoint().isEmpty()
                ? str(publicMap.get("auth_endpoint")) : session.authEndpoint());

        Map<String, Object> secrets = new LinkedHashMap<>();
        if (existing != null) secrets.putAll(asMap(existing.get("secrets")));
        secrets.put("access_token", accessToken);
        if (!refreshToken.isEmpty()) secrets.put("refresh_token", refreshToken);

        Map<String, Object> meta = new LinkedHashMap<>();
        if (existing != null) meta.putAll(asMap(existing.get("meta")));
        meta.put("updated_at", System.currentTimeMillis() / 1000.0);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ref", oauthRef);
        record.put("kind", CREDENTIAL_KIND_OAUTH_AUTH_CODE);
        record.put("public", publicMap);
        record.put("secrets", secrets);
        record.put("meta", meta);
        McpStore.saveCredential(oauthRef, record);

        // Attach OAuth credential ref + bearer binding to the card.
        Map<String, Object> card = McpStore.loadCard(session.clientKey());
        attachOauthCredential(card, oauthRef);
        McpStore.saveCard(session.clientKey(), card);
    }

    private void attachOauthCredential(Map<String, Object> card, String oauthRef) {
        Map<String, Object> credentials = asMap(card.get("credentials"));
        Map<String, Object> refSpec = new LinkedHashMap<>();
        refSpec.put("kind", CREDENTIAL_KIND_OAUTH_AUTH_CODE);
        refSpec.put("ref", oauthRef);
        credentials.put(CREDENTIAL_ALIAS_OAUTH, refSpec);
        card.put("credentials", credentials);

        Map<String, Object> endpoint = asMap(card.get("endpoint"));
        if (!McpModels.TRANSPORT_STDIO.equals(str(endpoint.get("transport")))) {
            Map<String, Object> headers = asMap(endpoint.get("headers"));
            Map<String, Object> authSpec = new LinkedHashMap<>();
            authSpec.put("source", "credential");
            authSpec.put("credential", CREDENTIAL_ALIAS_OAUTH);
            authSpec.put("field", "access_token");
            authSpec.put("format", "Bearer {value}");
            headers.put("Authorization", authSpec);
            endpoint.put("headers", headers);
            card.put("endpoint", endpoint);
        }
    }

    private void detachOauthCredential(Map<String, Object> card) {
        Map<String, Object> credentials = asMap(card.get("credentials"));
        credentials.remove(CREDENTIAL_ALIAS_OAUTH);
        card.put("credentials", credentials);

        Map<String, Object> endpoint = asMap(card.get("endpoint"));
        Object headersRaw = endpoint.get("headers");
        if (headersRaw instanceof Map<?, ?> hm) {
            Map<String, Object> headers = asMap(headersRaw);
            Object auth = headers.get("Authorization");
            if (auth instanceof Map<?, ?> m
                    && "credential".equals(str(m.get("source")))
                    && CREDENTIAL_ALIAS_OAUTH.equals(str(m.get("credential")))) {
                headers.remove("Authorization");
                endpoint.put("headers", headers);
                card.put("endpoint", endpoint);
            }
        }
    }

    // ------------------------------------------------------------------
    // Main flow: status / revoke
    // ------------------------------------------------------------------

    public McpModels.McpOAuthStatusResponse oauthStatus(String clientKey) {
        McpStore.loadCard(clientKey);
        Map<String, Object> oauth = McpStore.loadCredentialOrNull(
                McpStore.mcpOauthCredentialRef(clientKey));
        if (oauth == null || str(asMap(oauth.get("secrets")).get("access_token")).isEmpty()) {
            return new McpModels.McpOAuthStatusResponse(false, 0.0, "");
        }
        double expiresAt;
        try {
            expiresAt = Double.parseDouble(str(asMap(oauth.get("public")).get("expires_at")));
        } catch (Exception e) {
            expiresAt = 0.0;
        }
        boolean notExpired = expiresAt <= 0 || expiresAt > (System.currentTimeMillis() / 1000.0);
        return new McpModels.McpOAuthStatusResponse(
                notExpired,
                expiresAt,
                str(asMap(oauth.get("public")).get("scope")));
    }

    public McpModels.McpMessageResponse oauthRevoke(String clientKey) {
        Map<String, Object> card = McpStore.loadCard(clientKey);
        McpStore.deleteCredential(McpStore.mcpOauthCredentialRef(clientKey));
        detachOauthCredential(card);
        McpStore.saveCard(clientKey, card);
        return new McpModels.McpMessageResponse("OAuth tokens cleared");
    }

    /** Build the OAuth callback redirect URI for the current request. */
    public static String buildRedirectUri(String scheme, String serverName, int serverPort) {
        String base = scheme + "://" + serverName;
        if (!((scheme.equals("http") && serverPort == 80) || (scheme.equals("https") && serverPort == 443))) {
            base += ":" + serverPort;
        }
        return base + OAUTH_CALLBACK_PATH;
    }

    // ------------------------------------------------------------------
    // HTML popup helpers
    // ------------------------------------------------------------------

    private String makeErrorPage(String message) {
        String safe = htmlEscape(message);
        String body = "<p style='color:#c0392b;font-size:1.1em'>"
                + "<strong>Authorization failed</strong></p>"
                + "<p style='color:#666;font-size:13px'>" + safe + "</p>";
        return popupHtml("error", body, null);
    }

    private String popupHtml(String status, String bodyHtml, Map<String, Object> extraData) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "mcp-oauth");
        data.put("status", status);
        if (extraData != null) data.putAll(extraData);
        String jsonData;
        try {
            jsonData = MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            jsonData = "{}";
        }

        return "<!DOCTYPE html>\n"
                + "<html>\n<head>\n  <meta charset=\"utf-8\">\n"
                + "  <title>OAuth - Majo</title>\n"
                + "  <style>\n"
                + "    body {\n      font-family: -apple-system, BlinkMacSystemFont,\n"
                + "                   'Segoe UI', sans-serif;\n"
                + "      display: flex; align-items: center;\n"
                + "      justify-content: center;\n"
                + "      min-height: 100vh; margin: 0;\n"
                + "      background: #f8fafc;\n    }\n"
                + "    .card {\n      background: #fff; border-radius: 12px;\n"
                + "      padding: 40px 48px; text-align: center;\n"
                + "      box-shadow: 0 4px 24px rgba(0,0,0,.08);\n"
                + "      max-width: 400px;\n    }\n"
                + "    .close-btn {\n      margin-top: 20px; padding: 8px 28px;\n"
                + "      background: #4a90e2; color: #fff;\n"
                + "      border: none; border-radius: 6px;\n"
                + "      font-size: 14px; cursor: pointer;\n    }\n"
                + "    .close-btn:hover { background: #357abd; }\n"
                + "  </style>\n</head>\n<body>\n"
                + "  <div class=\"card\">\n    " + bodyHtml + "\n"
                + "    <button class=\"close-btn\" onclick=\"window.close()\">Close</button>\n"
                + "  </div>\n"
                + "  <script>\n"
                + "    (function () {\n"
                + "      var data = " + jsonData + ";\n"
                + "      var KEY = 'mcp_oauth_result';\n"
                + "      try { localStorage.setItem(KEY, JSON.stringify(data)); } catch (e) {}\n"
                + "      if (window.opener && !window.opener.closed) {\n"
                + "        try { window.opener.postMessage(data, '*'); } catch (e) {}\n"
                + "      }\n"
                + "      setTimeout(function () { window.close(); }, 1500);\n"
                + "    })();\n"
                + "  </script>\n</body>\n</html>";
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void purgeExpired() {
        stateStore.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) result.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return result;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String urlEncode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String htmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
