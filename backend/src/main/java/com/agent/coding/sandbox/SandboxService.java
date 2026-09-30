package com.agent.coding.sandbox;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sandbox routing (ADR-0012 phase 2): when the agent's running config sets
 * {@code sandbox.mode="appcontainer"} on Windows, {@code execute_command}
 * runs inside a per-workspace AppContainer instead of the plain hardened
 * process of phase 1. Default is {@code off} — phase 1 semantics — and any
 * bridge failure degrades to a readable error, never to an unsandboxed
 * execution of the very command the sandbox was asked to isolate.
 *
 * <p>One profile per workspace (deterministic name from its path), created
 * idempotently and kept across runs; the workspace gets a full inheritable
 * grant, {@code sandbox.extra_grant_paths} read/execute grants and
 * {@code sandbox.deny_paths} explicit denies. Network is closed unless
 * {@code sandbox.network_allow} (all-or-nothing; domain allowlists are a
 * known unenforced upstream config, reported the same way).
 */
@Service
public class SandboxService {

    public static final String MODE_OFF = "off";
    public static final String MODE_APPCONTAINER = "appcontainer";

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);

    /** Whether {@code execute_command} should route through the container. */
    public boolean enabledFor(String agentId) {
        return MODE_APPCONTAINER.equals(modeOf(agentId)) && AppContainerSandbox.isSupported();
    }

    /** Configured sandbox mode for an agent (normalised, default off). */
    public String modeOf(String agentId) {
        try {
            Map<String, Object> running = AgentStore.getRunningConfig(agentId);
            Map<String, Object> sandbox = SkillService.asMap(running.get("sandbox"));
            String mode = SkillService.str(sandbox.get("mode"), MODE_OFF)
                    .trim().toLowerCase();
            return mode.isBlank() ? MODE_OFF : mode;
        } catch (Exception e) {
            return MODE_OFF;
        }
    }

    /** Sandbox settings (grants/deny/network) for an agent. */
    public Map<String, Object> settingsFor(String agentId) {
        try {
            Map<String, Object> running = AgentStore.getRunningConfig(agentId);
            return new HashMap<>(SkillService.asMap(running.get("sandbox")));
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Run {@code command} inside the workspace's AppContainer. Returns the
     * same {@code [成功]/[错误]} shaped text as the plain path so the tool
     * contract is unchanged.
     */
    @SuppressWarnings("unchecked")
    public String run(String agentId, String command, Path workspace, int timeoutSeconds) {
        try {
            String containerName = containerNameFor(workspace);
            String sid = AppContainerSandbox.ensureProfile(
                    containerName, "Majo Agent Container (" + agentId + ")",
                    "majo execute_command isolation (ADR-0012)");
            AppContainerSandbox.grantPath(workspace.toString(), sid,
                    AppContainerSandbox.Access.WORKSPACE_FULL);

            Map<String, Object> settings = settingsFor(agentId);
            for (Object grant : asList(settings.get("extra_grant_paths"))) {
                Path p = Path.of(String.valueOf(grant));
                if (!Files.exists(p)) {
                    continue;
                }
                if (overlapsDeny(p, asList(settings.get("deny_paths")))) {
                    // Deny ACEs are not reliably enforced for AppContainer
                    // children on this platform (verified: a first-position
                    // deny-full ACE is ignored while the sibling allow for
                    // the same SID grants) — the only honest enforcement is
                    // by construction: an overlap drops the whole grant.
                    log.warn("[sandbox] grant '{}' dropped: overlaps a deny_paths entry", p);
                    continue;
                }
                AppContainerSandbox.grantPath(p.toString(), sid,
                        AppContainerSandbox.Access.READ_EXECUTE);
            }
            for (Object deny : asList(settings.get("deny_paths"))) {
                Path p = Path.of(String.valueOf(deny));
                if (Files.exists(p)) {
                    // Defense-in-depth only — see the overlap note above.
                    AppContainerSandbox.grantPath(p.toString(), sid,
                            AppContainerSandbox.Access.DENY_ALL);
                }
            }

            List<String> capabilities = Boolean.TRUE.equals(settings.get("network_allow"))
                    ? List.of("internetClient", "internetClientServer", "privateNetworkClientServer")
                    : List.of();
            String commandLine = "cmd.exe /c " + command;
            AppContainerSandbox.Result result = AppContainerSandbox.run(
                    commandLine, workspace.toString(), sid,
                    AppContainerSandbox.capabilitySids(capabilities), timeoutSeconds);
            return (result.exitCode() == 0 ? "[成功] " : "[警告] exit=" + result.exitCode() + " ")
                    + result.output();
        } catch (Exception e) {
            log.warn("[sandbox] appcontainer run failed for agent '{}': {}", agentId, e.getMessage());
            // Fail closed: never fall back to executing the command unsandboxed.
            return "[错误] 沙箱执行失败(" + e.getMessage() + ")；命令未执行。";
        }
    }

    /** Stable profile name for a workspace: {@code majo_<sha256[0:12]>}. */
    static String containerNameFor(Path workspace) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    workspace.toAbsolutePath().normalize().toString()
                            .toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("majo_");
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "majo_default";
        }
    }

    /**
     * True when {@code candidate} and any deny entry overlap (either
     * contains the other), case-insensitively on absolute paths. Deny ACEs
     * cannot be trusted to carve a hole out of a granted tree for
     * AppContainer children, so an overlap means the grant is unsafe.
     */
    static boolean overlapsDeny(Path candidate, List<Object> denyPaths) {
        String c = candidate.toAbsolutePath().normalize().toString().toLowerCase();
        for (Object deny : denyPaths) {
            String d = Path.of(String.valueOf(deny)).toAbsolutePath()
                    .normalize().toString().toLowerCase();
            if (c.equals(d) || c.startsWith(d + java.io.File.separator)
                    || d.startsWith(c + java.io.File.separator)) {
                return true;
            }
        }
        return false;
    }

    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }
}
