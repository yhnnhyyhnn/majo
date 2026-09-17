package com.agent.coding.auth;

import com.agent.coding.skill.SkillStore;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.file.Files;
import java.util.Map;

/**
 * Global bearer-token auth interceptor,
 * (auth middleware + {@code _PUBLIC_PATHS}).
 *
 * <p>Only active when {@code MAJO_AUTH_ENABLED} is set (Majo defaults to
 * auth disabled,). When active, every request must carry a
 * valid token from {@code WORKING_DIR/auth.json} unless the path is public
 * (auth/login, auth/status, auth/register, version, desktop/shutdown,
 * settings/language, settings/upload-limit, static assets) or the client IP
 * is in {@code security.allow_no_auth_hosts}.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final java.util.Set<String> PUBLIC_PATHS = java.util.Set.of(
            "/api/auth/login", "/api/auth/status", "/api/auth/register",
            "/api/desktop/shutdown", "/api/version",
            "/api/settings/language", "/api/settings/upload-limit",
            "/api/health", "/api/healthz");

    private static final java.util.Set<String> PUBLIC_PREFIXES = java.util.Set.of(
            "/assets/", "/api/frontend_plugin/");

    // allow_no_auth_hosts is consulted on every authenticated request but
    // changes only when agents.json is rewritten; cache it keyed by mtime
    // instead of re-parsing the file per request.
    private volatile long securityMtime = Long.MIN_VALUE;
    private volatile java.util.Set<String> noAuthHosts = java.util.Set.of();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!isAuthEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        if (isPublic(path) || isAllowedHost(request)) {
            return true;
        }
        String token = bearerToken(request.getHeader("Authorization"));
        if (token != null && isTokenValid(token)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"detail\":\"Not authenticated\"}");
        return false;
    }

    private boolean isAuthEnabled() {
        String flag = System.getenv("MAJO_AUTH_ENABLED");
        if (flag == null || flag.isBlank()) {
            return false;
        }
        return flag.trim().equalsIgnoreCase("true") || flag.trim().equals("1")
                || flag.trim().equalsIgnoreCase("yes");
    }

    private boolean isPublic(String path) {
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return path.equals("/") || path.equals("/index.html");
    }

    private boolean isAllowedHost(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        for (String host : noAuthHosts()) {
            if (remote.equals(host)) {
                return true;
            }
        }
        return "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
    }

    /** {@code security.allow_no_auth_hosts} from agents.json, reloaded on mtime change. */
    private java.util.Set<String> noAuthHosts() {
        java.nio.file.Path file = SkillStore.WORKING_DIR.resolve("agents.json");
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (mtime != securityMtime) {
                java.util.Set<String> hosts = new java.util.HashSet<>();
                Map<String, Object> config = SkillStore.readJson(file, Map.of());
                if (config.get("security") instanceof Map<?, ?> sec
                        && sec.get("allow_no_auth_hosts") instanceof java.util.List<?> list) {
                    for (Object h : list) {
                        hosts.add(String.valueOf(h));
                    }
                }
                noAuthHosts = hosts;
                securityMtime = mtime;
            }
        } catch (Exception ignored) {
            // Missing/unreadable file: serve the last known set (empty on first use)
        }
        return noAuthHosts;
    }

    @SuppressWarnings("unchecked")
    private boolean isTokenValid(String token) {
        try {
            java.nio.file.Path file = SkillStore.WORKING_DIR.resolve("auth.json");
            if (!Files.isRegularFile(file)) {
                return false;
            }
            Map<String, Object> auth = SkillStore.readJson(file, Map.of());
            Object tokens = auth.get("tokens");
            return tokens instanceof Map<?, ?> m && ((Map<?, ?>) m).containsKey(token);
        } catch (Exception e) {
            return false;
        }
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}
