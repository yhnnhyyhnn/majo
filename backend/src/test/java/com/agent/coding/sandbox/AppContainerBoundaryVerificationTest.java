package com.agent.coding.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The ADR-0012 phase-2 gate: automated verification, on this Windows
 * machine, that the AppContainer child is actually confined — kernel-state
 * evidence (the lowbox SID in the child's token groups) plus real
 * out-of-bounds probes that must fail. Skipped on non-Windows (CI is
 * Linux); on Windows it runs for real.
 */
@EnabledOnOs(OS.WINDOWS)
class AppContainerBoundaryVerificationTest {

    @TempDir
    Path workspace;

    @TempDir
    Path outside;

    private String containerName;
    private String sid;

    @BeforeEach
    void setUp() {
        assumeTrue(AppContainerSandbox.isSupported(), "Windows only");
        containerName = "majo_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        sid = AppContainerSandbox.ensureProfile(containerName,
                "Majo Sandbox Verification", "ADR-0012 boundary verification");
        assertNotNull(sid);
        assertTrue(sid.startsWith("S-1-15-2-"), "AppContainer SID shape, got: " + sid);
        AppContainerSandbox.grantPath(workspace.toString(), sid,
                AppContainerSandbox.Access.WORKSPACE_FULL);
    }

    @AfterEach
    void tearDown() {
        if (containerName != null) {
            AppContainerSandbox.deleteProfile(containerName);
        }
    }

    private AppContainerSandbox.Result run(String command) throws Exception {
        return AppContainerSandbox.run("cmd.exe /c " + command,
                workspace.toString(), sid, List.of(), 60);
    }

    @Test
    void profileSidShapeAndIdempotentCreate() {
        String again = AppContainerSandbox.ensureProfile(containerName,
                "Majo Sandbox Verification", "idempotent");
        assertEquals(sid, again, "re-create must reuse the existing profile SID");
    }

    @Test
    void childTokenIsStrippedOfAdminPrivileges() throws Exception {
        // Kernel-state proof of the lowbox token: the calling user here is
        // an administrator, but an AppContainer child keeps only a tiny
        // privilege set (SeChangeNotify/SeIncreaseWorkingSet) — SeDebug,
        // SeBackup et al. are gone. (whoami /groups renders group
        // attributes unchanged inside the container, so the privilege list
        // is the reliable discriminator.)
        AppContainerSandbox.Result r = run("whoami /priv");
        assertEquals(0, r.exitCode(), "whoami /priv must run: " + r.output());
        String out = r.output().toLowerCase();
        assertTrue(out.contains("sechangenotifyprivilege"), r.output());
        assertFalse(out.contains("sedebugprivilege"),
                "lowbox token must not carry SeDebugPrivilege: " + r.output());
        assertFalse(out.contains("setakeownershipprivilege"),
                "lowbox token must not carry SeTakeOwnershipPrivilege: " + r.output());
    }

    @Test
    void childResolvesWindowsNativeBinariesNotMsys() throws Exception {
        // When this JVM runs under Git Bash the inherited PATH carries
        // Git's usr\bin ahead of System32; without the sanitised env block
        // the container child resolves MSYS whoami and fails with GNU-style
        // "extra operand '/priv'". The env block must demote MSYS dirs.
        assumeTrue(System.getenv("PATH") != null
                        && System.getenv("PATH").toLowerCase().contains("git"),
                "meaningful only when the JVM PATH carries Git dirs (Git Bash launch)");
        AppContainerSandbox.Result r = run("whoami /priv");
        assertEquals(0, r.exitCode(), "must run: " + r.output());
        assertFalse(r.output().toLowerCase().contains("extra operand"),
                "MSYS whoami must not shadow the Windows binary in the container: "
                        + r.output());
    }

    @Test
    void inBoundsWriteSucceeds() throws Exception {
        AppContainerSandbox.Result r = run("echo sandbox-ok> in_probe.txt && type in_probe.txt");
        assertEquals(0, r.exitCode(), "in-bounds write: " + r.output());
        assertTrue(r.output().contains("sandbox-ok"), r.output());
        assertTrue(Files.readString(workspace.resolve("in_probe.txt")).contains("sandbox-ok"));
    }

    @Test
    void outOfBoundsReadIsDenied() throws Exception {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "TOPSECRET-MARKER");
        AppContainerSandbox.Result r = run("type \"" + secret + "\"");
        assertFalse(r.output().contains("TOPSECRET-MARKER"),
                "container must NOT read outside the workspace: " + r.output());
        String lowered = r.output().toLowerCase();
        assertTrue(lowered.contains("denied") || lowered.contains("拒绝"),
                "expected an access-denied marker, got: " + r.output());
    }

    @Test
    void outOfBoundsWriteIsDenied() throws Exception {
        Path target = outside.resolve("escape.txt");
        // cmd /c echo masks write failures via exit code; file presence is
        // the only trustworthy assertion.
        AppContainerSandbox.Result r = run("echo pwned> \"" + target + "\"");
        assertFalse(Files.exists(target),
                "container must NOT create files outside the workspace; cmd said: "
                        + r.output());
    }

    /**
     * Deny ACEs are NOT a reliable enforcement primitive for AppContainer
     * children on this platform — verified on Win10 19045: a first-position
     * inherited (or even file-explicit) deny-full ACE for the container SID
     * is ignored while the sibling allow for the same SID grants. The
     * enforceable contract is allow-list-by-construction, covered by
     * {@code SandboxServicePolicyTest} (grants overlapping denies are
     * dropped). This suite therefore keeps only the kernel-provable
     * properties: the boundary holds where no allow ACE exists.
     */
    @Test
    void ungrantedWorkspaceSubtreeIsStillReachableViaWorkspaceGrant() throws Exception {
        // Documents the grant model precisely: the workspace grant is
        // tree-wide (inheritable), so new files created by the PARENT inside
        // the workspace are readable — content the agent itself created must
        // remain visible to it across turns.
        Path note = workspace.resolve("parent-note.txt");
        Files.writeString(note, "parent-wrote-this");
        AppContainerSandbox.Result r = run("type \"" + note + "\"");
        assertEquals(0, r.exitCode(), "workspace grant must cover parent-created files");
        assertTrue(r.output().contains("parent-wrote-this"));
    }
}
