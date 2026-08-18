package com.agent.coding.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Prints the desktop sidecar ready line to stdout once Tomcat is listening.
 *
 * <p>Contract with the Rust shell ({@code src/backend/events.rs}): the shell
 * watches the child process stdout and parses lines of the form
 * {@code MAJO_BACKEND_READY {"port":N}} to learn which port the backend is
 * actually bound to. The line must be a single stdout line with that exact
 * prefix, followed by JSON with a {@code port} field.
 *
 * <p>Only active when the desktop shell injected {@code MAJO_DESKTOP_APP=1};
 * plain browser/docker deployments skip it so stdout stays clean.
 */
@Component
public class DesktopReadyPrinter implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(DesktopReadyPrinter.class);
    private static final String READY_PREFIX = "MAJO_BACKEND_READY ";

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (System.getenv("MAJO_DESKTOP_APP") == null) {
            return;
        }
        int port = event.getWebServer().getPort();
        // Single line, exact prefix, then JSON — matches events.rs ready_port_from_stdout.
        System.out.println(READY_PREFIX + "{\"port\":" + port + "}");
        System.out.flush();
        log.info("[desktop] published ready line for port {}", port);
    }
}
