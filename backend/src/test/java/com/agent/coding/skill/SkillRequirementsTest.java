package com.agent.coding.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for skill requirement parsing and dependency checks (#7609 port). */
class SkillRequirementsTest {

    @Test
    void emptyFrontmatterYieldsEmptyRequirements() {
        var parsed = SkillStore.parseSkillRequirements(Map.of());
        assertEquals(0, parsed.errors().size());
        assertEquals(SkillStore.SkillRequirements.EMPTY, parsed.requirements());
    }

    @Test
    void bareListIsTreatedAsBins() {
        var parsed = SkillStore.parseSkillRequirements(
                Map.of("requires", List.of("ffmpeg", "git")));
        assertEquals(List.of("ffmpeg", "git"), parsed.requirements().requireBins());
        assertEquals(List.of(), parsed.requirements().requireEnvs());
        assertEquals(List.of(), parsed.requirements().requireMcps());
        assertEquals(0, parsed.errors().size());
    }

    @Test
    void mapShapeParsesAllKeys() {
        var parsed = SkillStore.parseSkillRequirements(Map.of(
                "requires", Map.of(
                        "bins", List.of("node"),
                        "env", List.of("GITHUB_TOKEN"),
                        "mcp", List.of("github"))));
        assertEquals(List.of("node"), parsed.requirements().requireBins());
        assertEquals(List.of("GITHUB_TOKEN"), parsed.requirements().requireEnvs());
        assertEquals(List.of("github"), parsed.requirements().requireMcps());
        assertEquals(0, parsed.errors().size());
    }

    @Test
    void legacyQwenpawMetadataShapeWithBinAlias() {
        var parsed = SkillStore.parseSkillRequirements(Map.of(
                "metadata", Map.of("qwenpaw", Map.of("requires", Map.of(
                        "bin", List.of("jq"),
                        "env", List.of("X"))))));
        assertEquals(List.of("jq"), parsed.requirements().requireBins());
        assertEquals(List.of("X"), parsed.requirements().requireEnvs());
    }

    @Test
    void invalidValuesAreReportedAsErrors() {
        var parsed = SkillStore.parseSkillRequirements(Map.of(
                "requires", Map.of(
                        "bins", List.of(""),
                        "env", "not-a-list")));
        assertEquals(List.of(), parsed.requirements().requireBins());
        assertEquals(List.of(), parsed.requirements().requireEnvs());
        assertEquals(2, parsed.errors().size());
    }

    @Test
    void valuesAreStrippedAndDeduplicated() {
        var parsed = SkillStore.parseSkillRequirements(Map.of(
                "requires", Map.of("bins", List.of("git", " git ", "git"))));
        assertEquals(List.of("git"), parsed.requirements().requireBins());
    }

    @Test
    void nonMappingRequiresIsAnError() {
        var parsed = SkillStore.parseSkillRequirements(Map.of("requires", 42));
        assertEquals(SkillStore.SkillRequirements.EMPTY, parsed.requirements());
        assertEquals(1, parsed.errors().size());
    }

    // ── Dependency checks ────────────────────────────────────────────

    @Test
    void missingEnvAndBinsAreReported() {
        var req = new SkillStore.SkillRequirements(
                List.of("definitely-not-a-real-binary-xyz"),
                List.of("DEFINITELY_NOT_SET_VAR_XYZ"), List.of());
        var missing = SkillDependencyChecker.check(req, System.getenv(), null);
        assertEquals(2, missing.size());
        assertTrue(missing.get(0).contains("DEFINITELY_NOT_SET_VAR_XYZ")
                || missing.get(1).contains("DEFINITELY_NOT_SET_VAR_XYZ"));
    }

    @Test
    void metEnvPrerequisiteProduceNoFindings() {
        var req = new SkillStore.SkillRequirements(
                List.of(), List.of("PATH"), List.of());
        var missing = SkillDependencyChecker.check(req, System.getenv(), null);
        assertEquals(0, missing.size());
    }

    @Test
    void whichFindsKnownBinaryAndMissesUnknown() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String known = windows ? "cmd.exe" : "sh";
        assertNotNull(SkillDependencyChecker.which(known));
        assertNull(SkillDependencyChecker.which("definitely-not-a-real-binary-xyz"));
    }
}
