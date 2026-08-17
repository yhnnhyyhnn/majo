package com.agent.coding.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.SpringApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Desktop-only protocol endpoint for the Tauri shell (Tauri shell style
 * sidecar contract).
 *
 * <p>The Rust shell expects two things from the backend:
 * <ol>
 *   <li>A ready line {@code QWENPAW_BACKEND_READY {"port":N}} printed to
 *       stdout once Tomcat is listening (see {@link DesktopReadyPrinter});
 *       the shell parses the port out of it.</li>
 *   <li>A {@code POST /api/desktop/shutdown} endpoint guarded by the
 *       {@code X-QwenPaw-Desktop-Shutdown-Token} header matching the
 *       {@code QWENPAW_DESKTOP_SHUTDOWN_TOKEN} env var the shell injects.
 *       The shell calls it on quit so the sidecar can drain gracefully
 *       instead of being force-killed.</li>
 * </ol>
 *
 * <p>The frontend bootstrap gate polls {@code /api/version} (already provided
 * by {@link ConsoleController}) to decide when the backend is ready to serve
 * the SPA.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DesktopController {

    private static final Logger log = LoggerFactory.getLogger(DesktopController.class);

    static final String SHUTDOWN_TOKEN_ENV = "QWENPAW_DESKTOP_SHUTDOWN_TOKEN";
    static final String SHUTDOWN_TOKEN_HEADER = "X-QwenPaw-Desktop-Shutdown-Token";

    private final ApplicationContext context;

    public DesktopController(ApplicationContext context) {
        this.context = context;
    }

    /**
     * Graceful shutdown requested by the Tauri shell on quit.
     *
     * <p>The shell only accepts a successful status code; the actual drain is
     * handled by Spring's normal shutdown hooks (the request returns
     * immediately, matching the reference implementation's "flip the flag and
     * return" behavior).
     */
    @PostMapping("/desktop/shutdown")
    public ResponseEntity<Map<String, Object>> shutdown(
            @RequestHeader(value = SHUTDOWN_TOKEN_HEADER, required = false) String token) {
        String expected = System.getenv(SHUTDOWN_TOKEN_ENV);
        if (expected == null || expected.isEmpty()) {
            log.warn("[desktop] shutdown requested but no token configured; ignoring");
            return ResponseEntity.status(403).body(Map.of("error", "shutdown token not configured"));
        }
        if (!expected.equals(token)) {
            log.warn("[desktop] shutdown requested with invalid token");
            return ResponseEntity.status(401).body(Map.of("error", "invalid shutdown token"));
        }

        log.info("[desktop] graceful shutdown requested; exiting");
        // The request thread must return before the context closes, otherwise
        // the response can never be flushed back to the shell. Use a daemon
        // thread so a wedged drain never keeps the JVM alive.
        Thread closer = new Thread(() -> {
            int exitCode = SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }, "desktop-shutdown");
        closer.setDaemon(true);
        closer.start();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "shutting_down");
        return ResponseEntity.ok(resp);
    }
}
