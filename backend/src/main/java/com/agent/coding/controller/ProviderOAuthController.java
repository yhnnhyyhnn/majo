package com.agent.coding.controller;

import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.repository.ProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider OAuth flow,.
 *
 * <p>Currently supports OpenRouter: redirect to openrouter.ai/auth, exchange
 * the returned code for a permanent API key, and persist it on the provider.
 * The callback returns an HTML page that postMessages the opener and closes
 * the popup, matching the frontend contract.
 */
@RestController
@RequestMapping("/api/providers")
@CrossOrigin(origins = "*")
public class ProviderOAuthController {

    private static final Logger log = LoggerFactory.getLogger(ProviderOAuthController.class);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    static final class OAuthSession {
        final String providerId;
        final String callbackUrl;
        volatile String status = "pending"; // pending | completed | failed
        volatile String error;
        volatile long createdAt = System.currentTimeMillis();

        OAuthSession(String providerId, String callbackUrl) {
            this.providerId = providerId;
            this.callbackUrl = callbackUrl;
        }
    }

    private final ProviderRepository providerRepo;
    private final ConcurrentHashMap<String, OAuthSession> sessions = new ConcurrentHashMap<>();

    public ProviderOAuthController(ProviderRepository providerRepo) {
        this.providerRepo = providerRepo;
    }

    @PostMapping("/{provider_id}/oauth/start")
    public ResponseEntity<?> startOAuth(@PathVariable String provider_id,
                                        jakarta.servlet.http.HttpServletRequest request) {
        if (!"openrouter".equals(provider_id)) {
            return ResponseEntity.notFound().build();
        }
        String state = UUID.randomUUID().toString();
        String base = request.getScheme() + "://" + request.getHeader("Host");
        String callbackUrl = base + "/api/providers/" + provider_id + "/oauth/callback";
        String authorizeUrl = "https://openrouter.ai/auth?callback_url="
                + URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);
        sessions.put(state, new OAuthSession(provider_id, callbackUrl));
        return ResponseEntity.ok(Map.of(
                "authorize_url", authorizeUrl,
                "state", state,
                "flow_type", "browser_redirect"
        ));
    }

    @GetMapping("/{provider_id}/oauth/callback")
    public ResponseEntity<String> oauthCallback(@PathVariable String provider_id,
                                                @RequestParam String code,
                                                @RequestParam(defaultValue = "") String state) {
        OAuthSession session = sessions.get(state);
        if (session == null || !session.providerId.equals(provider_id)) {
            return ResponseEntity.badRequest().body(errorHtml("Session expired or invalid."));
        }
        try {
            String apiKey = exchangeCode(code);
            ProviderEntity provider = providerRepo.findById(provider_id).orElse(null);
            if (provider == null) {
                provider = new ProviderEntity();
                provider.setId(provider_id);
                provider.setName(provider_id);
                provider.setBaseUrl("https://openrouter.ai/api/v1");
            }
            provider.setApiKey(apiKey);
            providerRepo.save(provider);
            session.status = "completed";
            log.info("[oauth] provider {} authorized", provider_id);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(successHtml(provider_id));
        } catch (Exception e) {
            session.status = "failed";
            session.error = e.getMessage();
            log.warn("[oauth] exchange failed for {}: {}", provider_id, e.getMessage());
            return ResponseEntity.status(500).body(errorHtml("Authorization failed. Please retry."));
        }
    }

    @GetMapping("/{provider_id}/oauth/status")
    public Map<String, Object> oauthStatus(@PathVariable String provider_id,
                                           @RequestParam String state) {
        OAuthSession session = sessions.get(state);
        if (session == null) {
            return Map.of("status", "failed", "error", "Session expired");
        }
        if (!session.providerId.equals(provider_id)) {
            return Map.of("status", "failed", "error", "Provider mismatch");
        }
        return Map.of("status", session.status, "error", session.error == null ? "" : session.error);
    }

    private static String exchangeCode(String code) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/auth/keys"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code + "\"}"))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("OpenRouter key exchange returned HTTP " + resp.statusCode());
        }
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(resp.body());
        String key = node.path("key").asText("");
        if (key.isBlank()) {
            throw new IllegalStateException("No key in OpenRouter response");
        }
        return key;
    }

    private static String successHtml(String providerId) {
        return "<!DOCTYPE html><html><head><title>Authorization Successful</title></head>"
                + "<body style=\"font-family:system-ui;text-align:center;padding:60px\">"
                + "<h2>Connected!</h2><p>You can close this window.</p>"
                + "<script>if (window.opener) { window.opener.postMessage("
                + "{type: \"oauth_complete\", provider: \"" + providerId + "\"}, window.location.origin); }"
                + "setTimeout(function() { window.close(); }, 1500);</script></body></html>";
    }

    private static String errorHtml(String message) {
        return "<!DOCTYPE html><html><head><title>Authorization Failed</title></head>"
                + "<body style=\"font-family:system-ui;text-align:center;padding:60px\">"
                + "<h2>Authorization Failed</h2><p>" + message + "</p>"
                + "<p><a href=\"javascript:window.close()\">Close this window</a></p></body></html>";
    }
}
