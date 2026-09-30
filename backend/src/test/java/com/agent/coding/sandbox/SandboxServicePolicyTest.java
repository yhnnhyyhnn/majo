package com.agent.coding.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sandbox policy is allow-list-by-construction (ADR-0012 phase 2): deny
 * ACEs are not reliably enforced for AppContainer children (verified on
 * Win10 19045 — a first-position deny-full ACE is ignored while the
 * sibling allow for the same SID grants), so a grant that overlaps a deny
 * entry must be dropped entirely instead of trusting the ACE.
 */
class SandboxServicePolicyTest {

    @TempDir
    Path root;

    @Test
    void grantDroppedWhenDenyEqualsOrContainsIt() {
        Path tools = root.resolve("tools");
        Path toolsSecret = root.resolve("tools").resolve("secrets");
        List<Object> denies = List.of(toolsSecret.toString());
        assertTrue(SandboxService.overlapsDeny(tools, denies),
                "deny inside the granted tree makes the grant unsafe");
    }

    @Test
    void grantDroppedWhenDenyIsInsideIt() {
        Path drive = root.resolve("tools");
        List<Object> denies = List.of(drive.resolve("sub").resolve("key.md").toString());
        assertTrue(SandboxService.overlapsDeny(drive, denies),
                "a deny anywhere below the grant poisons the whole grant");
    }

    @Test
    void unrelatedGrantSurvives() {
        Path tools = root.resolve("tools");
        Path other = root.resolve("other-suite");
        assertFalse(SandboxService.overlapsDeny(tools, List.of(other.toString())));
    }

    @Test
    void comparisonIsCaseAndSeparatorInsensitive() {
        Path tools = root.resolve("Tools");
        Path deny = root.resolve("TOOLS").resolve("secrets");
        assertTrue(SandboxService.overlapsDeny(tools, List.of(deny.toString())),
                "Windows paths are case-insensitive; mixed separators must match too");
    }
}
