package com.agent.coding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal readiness probe for container healthchecks (see docker-compose.yml).
 *
 * <p>Exit 0 when the backend's /api/health responds 200, non-zero otherwise.
 * Uses the JDK's own HTTP client so no curl/wget is needed in the runtime
 * image (eclipse-temurin:21-jre-alpine has none).
 *
 * <p>Usage: {@code java -cp /opt/majo.jar com.agent.coding.HealthCheckCli http://127.0.0.1:18789/api/health}
 */
public final class HealthCheckCli {

    private static final int TIMEOUT_SECONDS = 5;

    private HealthCheckCli() {
    }

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://127.0.0.1:18789/api/health";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                System.out.println("healthy: " + response.statusCode());
                System.exit(0);
            }
            System.err.println("unhealthy: status " + response.statusCode());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("unhealthy: " + e.getMessage());
            System.exit(1);
        }
    }
}
