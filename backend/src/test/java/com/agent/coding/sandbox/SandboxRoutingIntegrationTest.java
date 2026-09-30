package com.agent.coding.sandbox;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.agent.AgentStore;
import com.agent.coding.tool.ExecuteCommandTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end sandbox routing (ADR-0012 phase 2): the real
 * {@code execute_command} tool, through the real running-config resolution,
 * must land inside the AppContainer when {@code sandbox.mode=appcontainer}
 * (kernel proof: the child's stripped privilege list) and stay on the
 * legacy hardened path when {@code off}. The boundary suite drives
 * {@link AppContainerSandbox} directly; this one proves the wiring.
 */
@SpringBootTest
@EnabledOnOs(OS.WINDOWS)
class SandboxRoutingIntegrationTest {

    @TempDir
    Path workspace;

    @Autowired
    private ExecuteCommandTool tool;

    private String agentId;

    /** Register a throwaway agent whose workspace IS the temp dir, then flip the sandbox flag. */
    private void registerAgent(String sandboxMode) {
        agentId = workspace.getFileName().toString();
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", agentId);
        spec.put("workspace_dir", workspace.toString());
        AgentStore.createAgent(spec);

        Map<String, Object> running = AgentStore.getRunningConfig(agentId);
        Map<String, Object> sandbox = new LinkedHashMap<>();
        sandbox.put("mode", sandboxMode);
        sandbox.put("network_allow", false);
        running.put("sandbox", sandbox);
        AgentStore.saveRunningConfig(agentId, running, null);

        WorkspaceContext.set(workspace.toString());
    }

    @AfterEach
    void cleanUp() {
        WorkspaceContext.clear();
        if (agentId != null) {
            try {
                AgentStore.deleteAgent(agentId);
            } catch (Exception ignored) {
            }
            try {
                AppContainerSandbox.deleteProfile(
                        SandboxService.containerNameFor(workspace));
            } catch (Exception ignored) {
            }
        }
    }

    /** Absolute path: the legacy path's UserBinPaths prepends Git's usr/bin
     * where MSYS whoami shadows the Windows one; the container path inherits
     * the JVM's real PATH. Pinning the binary keeps both routes comparable. */
    private static final String WHOAMI = "%SystemRoot%\\System32\\whoami.exe";

    @Test
    void appcontainerModeRoutesThroughTheContainer() {
        registerAgent("appcontainer");

        String out = tool.executeCommand("whoami /priv", null);
        assertTrue(out.contains("SeChangeNotifyPrivilege"),
                "command must have run: " + out);
        // Kernel proof of routing: an AppContainer child has no SeDebug; the
        // legacy path (this user is an administrator) would list it.
        assertFalse(out.contains("SeDebugPrivilege"),
                "appcontainer mode must execute inside the lowbox container: " + out);
    }

    @Test
    void offModeStaysOnTheLegacyPath() {
        registerAgent("off");

        String out = tool.executeCommand(WHOAMI + " /priv", null);
        assertTrue(out.contains("SeChangeNotifyPrivilege"),
                "command must have run: " + out);
        assertTrue(out.contains("SeDebugPrivilege"),
                "mode=off must keep the legacy administrator path: " + out);
    }

    @Test
    void unknownModeFallsBackToLegacyPath() {
        registerAgent("hypervisor");

        String out = tool.executeCommand(WHOAMI + " /priv", null);
        assertTrue(out.contains("SeChangeNotifyPrivilege"),
                "command must have run: " + out);
        assertTrue(out.contains("SeDebugPrivilege"),
                "unknown sandbox modes must not enable the container: " + out);
        assertFalse(new SandboxService().modeOf(agentId)
                        .equals(SandboxService.MODE_APPCONTAINER),
                "sanity: modeOf normalises unknown values off appcontainer");
    }
}
