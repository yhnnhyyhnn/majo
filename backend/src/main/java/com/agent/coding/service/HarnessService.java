package com.agent.coding.service;

import com.agent.coding.skill.SkillStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Third-party agent harness catalog + status
 * harnesses/registry.py + app/routers/harnesses.py.
 *
 * <p>Reports supported third-party coding agents (Codex, Qoder; Claude Code
 * coming soon) with live installation / authentication status. Majo does not
 * embed the full harness runtime (Codex/Qoder app-server adapters) — the
 * catalog and probes let the frontend show the harness UI and gate actions on
 * binary availability; driving an actual session is a future integration.
 */
@Service
public class HarnessService {

    private static final Logger log = LoggerFactory.getLogger(HarnessService.class);

    private Path stateDir() {
        return SkillStore.WORKING_DIR.resolve("harnesses");
    }

    // ── catalog ─────────────────────────────────────────────────────────

    private static Map<String, Object> command(String name, String description, boolean acceptsArguments) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("description", description);
        c.put("accepts_arguments", acceptsArguments);
        return c;
    }

    private static Map<String, Object> preset(String id, String name, String description, Map<String, Object> settings) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("name", name);
        p.put("description", description);
        p.put("settings", settings);
        return p;
    }

    private static Map<String, Object> codexCapabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("authentication", true);
        caps.put("model_selection", true);
        caps.put("reasoning_effort", true);
        caps.put("reasoning_stream", true);
        caps.put("tool_stream", true);
        caps.put("session_resume", true);
        caps.put("workspace_ui", false);
        caps.put("native_skills_ui", false);
        caps.put("native_tools_ui", false);
        caps.put("native_mcp_ui", false);
        caps.put("loop_modes", false);
        caps.put("attachments", true);
        caps.put("context_usage", false);
        caps.put("skills_commands", false);
        caps.put("majo_skills_projection", true);
        caps.put("majo_mcp_projection", true);
        caps.put("provider_skills_discovery", true);
        caps.put("provider_mcp_discovery", true);
        caps.put("mcp_tool_allowlist", true);
        caps.put("commands", List.of(
                command("compact", "Compact the current Codex thread", false),
                command("review", "Review uncommitted workspace changes", false),
                command("skills", "List skills available to Codex", false),
                command("status", "Show Codex account and session status", false)));
        caps.put("approval_presets", List.of(
                preset("ask", "Ask before changes",
                        "Allow workspace changes and ask before elevated actions.",
                        Map.of("sandbox", "workspace-write", "approval_policy", "on-request")),
                preset("read-only", "Read only", "Inspect files without changing them.",
                        Map.of("sandbox", "read-only", "approval_policy", "on-request")),
                preset("workspace", "Workspace access", "Allow workspace changes without confirmation.",
                        Map.of("sandbox", "workspace-write", "approval_policy", "never")),
                preset("full-access", "Full access",
                        "Allow unrestricted local execution without confirmation.",
                        Map.of("sandbox", "danger-full-access", "approval_policy", "never"))));
        return caps;
    }

    private static Map<String, Object> qoderCapabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("authentication", true);
        caps.put("model_selection", true);
        caps.put("reasoning_effort", true);
        caps.put("reasoning_stream", true);
        caps.put("tool_stream", true);
        caps.put("session_resume", true);
        caps.put("workspace_ui", false);
        caps.put("native_skills_ui", false);
        caps.put("native_tools_ui", false);
        caps.put("native_mcp_ui", false);
        caps.put("loop_modes", false);
        caps.put("attachments", true);
        caps.put("context_usage", false);
        caps.put("skills_commands", false);
        caps.put("majo_skills_projection", true);
        caps.put("majo_mcp_projection", true);
        caps.put("provider_skills_discovery", true);
        caps.put("provider_mcp_discovery", false);
        caps.put("mcp_tool_allowlist", true);
        caps.put("commands", List.of(
                command("compact", "Compact the current Qoder session", false)));
        caps.put("approval_presets", List.of(
                preset("ask", "Ask before actions", "Ask before file changes and command execution.",
                        Map.of("permission_mode", "default")),
                preset("accept-edits", "Accept edits", "Allow file edits while keeping other safeguards.",
                        Map.of("permission_mode", "acceptEdits")),
                preset("plan", "Plan only", "Analyze and plan without changing files.",
                        Map.of("permission_mode", "plan")),
                preset("auto", "Automatic", "Let Qoder decide which safe actions can run.",
                        Map.of("permission_mode", "auto")),
                preset("full-access", "Full access", "Skip permission checks in a trusted workspace.",
                        Map.of("permission_mode", "bypassPermissions"))));
        return caps;
    }

    private static Map<String, Object> claudeCapabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("authentication", false);
        caps.put("model_selection", false);
        caps.put("reasoning_effort", false);
        caps.put("reasoning_stream", false);
        caps.put("tool_stream", false);
        caps.put("session_resume", false);
        caps.put("workspace_ui", false);
        caps.put("native_skills_ui", false);
        caps.put("native_tools_ui", false);
        caps.put("native_mcp_ui", false);
        caps.put("loop_modes", false);
        caps.put("attachments", false);
        caps.put("context_usage", false);
        caps.put("skills_commands", false);
        caps.put("majo_skills_projection", false);
        caps.put("majo_mcp_projection", false);
        caps.put("provider_skills_discovery", false);
        caps.put("provider_mcp_discovery", false);
        caps.put("mcp_tool_allowlist", false);
        caps.put("commands", List.of());
        caps.put("approval_presets", List.of());
        return caps;
    }

    private record CatalogItem(String id, String name, boolean comingSoon, Map<String, Object> capabilities) {}

    private static final List<CatalogItem> CATALOG = List.of(
            new CatalogItem("codex", "Codex", false, codexCapabilities()),
            new CatalogItem("claude", "Claude Code", true, claudeCapabilities()),
            new CatalogItem("qoder", "Qoder", false, qoderCapabilities()));

    public CatalogItem catalogItem(String providerId) {
        for (CatalogItem item : CATALOG) {
            if (item.id.equals(providerId)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Unknown third-party agent backend: " + providerId);
    }

    /** Capabilities map for a provider (throws for unknown providers). */
    public Map<String, Object> capabilities(String providerId) {
        return catalogItem(providerId).capabilities();
    }

    // ── binary detection ────────────────────────────────────────────────

    /** Resolve a CLI binary from PATH (or configured path),discovery. */
    private static String resolveBinary(String binary) {
        if (binary == null || binary.isBlank()) {
            return null;
        }
        if (Files.isExecutable(Path.of(binary))) {
            return binary;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, binary);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    // ── auth storage ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadAuth() {
        Path file = stateDir().resolve("auth.json");
        if (Files.isRegularFile(file)) {
            Map<String, Object> data = SkillStore.readJson(file, Map.of());
            return data.isEmpty() ? new LinkedHashMap<>() : data;
        }
        return new LinkedHashMap<>();
    }

    private void saveAuth(Map<String, Object> auth) {
        try {
            Files.createDirectories(stateDir());
        } catch (IOException e) {
            log.warn("Cannot create harness state dir: {}", e.getMessage());
        }
        SkillStore.writeJsonAtomic(stateDir().resolve("auth.json"), auth);
    }

    private boolean isAuthenticated(String providerId) {
        Map<String, Object> auth = loadAuth();
        Object token = auth.get(providerId);
        return token instanceof String s && !s.isBlank();
    }

    // ── provider status ─────────────────────────────────────────────────

    public Map<String, Object> providerStatus(String providerId, Map<String, Object> settings) {
        CatalogItem item = catalogItem(providerId);
        String configuredBinary = settings.get("binary") == null
                ? null : String.valueOf(settings.get("binary"));
        String binaryName = item.id.equals("codex") ? "codex"
                : item.id.equals("qoder") ? "qoder" : null;
        String runtimePath = resolveBinary(configuredBinary != null ? configuredBinary : binaryName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.id);
        result.put("name", item.name);
        result.put("available", !item.comingSoon);
        result.put("coming_soon", item.comingSoon);
        result.put("installed", runtimePath != null);
        result.put("authenticated", isAuthenticated(item.id));
        result.put("account", isAuthenticated(item.id)
                ? Map.of("type", "local", "username", null, "email", null, "planType", null)
                : null);
        result.put("runtime_path", runtimePath);
        result.put("runtime_source", runtimePath == null ? null : "path");
        result.put("error", null);
        result.put("capabilities", item.capabilities());
        return result;
    }

    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CatalogItem item : CATALOG) {
            result.add(providerStatus(item.id, Map.of()));
        }
        return result;
    }

    // ── login / logout ──────────────────────────────────────────────────

    public Map<String, Object> login(String providerId, boolean deviceCode, Map<String, Object> settings) {
        CatalogItem item = catalogItem(providerId);
        if (item.comingSoon) {
            throw new IllegalArgumentException(item.name + " is not available yet");
        }
        if (resolveBinary(item.id.equals("codex") ? "codex" : "qoder") == null) {
            throw new IllegalArgumentException(item.name + " CLI is not installed");
        }
        // Majo stores a local auth marker; real provider OAuth is a future
        // integration when the harness runtime is ported.
        Map<String, Object> auth = loadAuth();
        auth.put(providerId, "local-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        saveAuth(auth);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", deviceCode ? "device_code" : "browser_redirect");
        result.put("loginId", java.util.UUID.randomUUID().toString());
        return result;
    }

    public boolean logout(String providerId) {
        Map<String, Object> auth = loadAuth();
        if (auth.remove(providerId) != null) {
            saveAuth(auth);
            return true;
        }
        return false;
    }
}
