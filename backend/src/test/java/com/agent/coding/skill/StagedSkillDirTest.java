package com.agent.coding.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Staging path-injection hardening (qwenpaw #8065): the temp-root prefix is
 * fixed and the child resolves through safeSkillDir, so a raw skill name —
 * e.g. taken verbatim from a zip entry — can never place the staging
 * directory outside the temp root.
 */
class StagedSkillDirTest {

    @TempDir
    Path tempBase;

    @Test
    void normalNameStagesInsideTempRoot() throws Exception {
        Path staged = SkillService.stagedSkillDir("my-skill");
        try {
            Path expectedParent = staged.getParent().toAbsolutePath().normalize();
            assertTrue(expectedParent.getFileName().toString().startsWith("majo_skill_stage_"),
                    "temp root prefix is fixed, name-free: " + expectedParent);
            assertEquals("my-skill", staged.getFileName().toString());
            assertTrue(Files.exists(staged.getParent()));
        } finally {
            SkillStore.deleteRecursively(staged.getParent());
        }
    }

    @Test
    void traversalNameIsRejected() {
        assertThrows(SkillsError.class, () -> SkillService.stagedSkillDir("../escape"));
        assertThrows(SkillsError.class, () -> SkillService.stagedSkillDir("a/b"));
        assertThrows(SkillsError.class, () -> SkillService.stagedSkillDir("a\\b"));
        assertThrows(SkillsError.class, () -> SkillService.stagedSkillDir(".."));
        // Nothing escaped into the surrounding temp dir.
        try (var entries = Files.list(tempBase)) {
            assertTrue(entries.findAny().isEmpty(), "no stray staging dirs created");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
