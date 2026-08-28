package com.agent.coding.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback so the desktop WebView (which loads the console at
 * {@code /console}, matching the shell's {@code backendConsoleUrl})
 * gets the same React app as the root path.
 *
 * <p>The frontend already detects the {@code /console} prefix and sets its
 * router basename accordingly ({@code getRouterBasename} in App.tsx), so we
 * only need to serve {@code index.html} for the console paths. API routes live
 * under {@code /api} (see {@code getApiUrl} which always prefixes), so this
 * catch-all never shadows backend endpoints.
 */
@Controller
public class SpaFallbackController {

    /**
     * Serve index.html for /console and /console/... so React Router handles
     * deep links (e.g. /console/settings). The forward preserves the original
     * URL, keeping the basename detection in App.tsx working.
     *
     * <p>Asset requests (e.g. /console/assets/index-*.js — the React Router
     * basename prefixes every hashed bundle URL) must resolve to the real
     * files under static /assets/, not to index.html: a text/html response
     * for a module script fails strict MIME checking and blanks the window.
     * Forwarding them (minus the /console prefix) lets Spring's static
     * resource handler serve the actual bundles.
     */
    @GetMapping(value = {"/console", "/console/**"})
    public String consoleSpa(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/console/assets/") || path.startsWith("/console/online.svg")
                || path.startsWith("/console/logo-")) {
            return "forward:" + path.substring("/console".length());
        }
        return "forward:/index.html";
    }
}
