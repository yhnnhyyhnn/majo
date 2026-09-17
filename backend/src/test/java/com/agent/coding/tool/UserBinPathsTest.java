package com.agent.coding.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User-bin PATH injection (QwenPaw #7057 counterpart): existing dirs come
 * first when already on PATH, only-existing candidates are added, and
 * duplicates are never introduced.
 */
class UserBinPathsTest {

    @TempDir
    Path home;

    @Test
    void prependsExistingUserBinDirsBeforeExistingPath() throws Exception {
        Path bin = Files.createDirectories(home.resolve(".local").resolve("bin"));
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));

        UserBinPaths.applyTo(env, home.toString(), false);

        String path = env.get("PATH");
        List<String> parts = List.of(path.split(java.io.File.pathSeparator));
        assertEquals(bin.toString(), parts.get(0), "user bin first");
        assertTrue(parts.contains("/usr/bin"), "existing PATH preserved");
    }

    @Test
    void skipsNonExistentCandidates() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        // home has no .local/bin → unix candidates don't exist on disk
        UserBinPaths.applyTo(env, home.toString(), false);
        assertEquals("/usr/bin", env.get("PATH"), "nothing added when candidates don't exist");
    }

    @Test
    void noDuplicateWhenAlreadyOnPath() throws Exception {
        Path bin = Files.createDirectories(home.resolve(".local").resolve("bin"));
        String binNorm = bin.toString().replace('\\', '/');
        Map<String, String> env = new HashMap<>();
        env.put("PATH", binNorm + java.io.File.pathSeparator + "/usr/bin");

        UserBinPaths.applyTo(env, home.toString(), false);

        long count = List.of(env.get("PATH").split(java.io.File.pathSeparator)).stream()
                .filter((p) -> p.replace('\\', '/').equalsIgnoreCase(binNorm))
                .count();
        assertEquals(1, count, "no duplicate entries");
    }

    @Test
    void unixCandidateDirsListed() {
        String sep = java.io.File.separator;
        List<String> dirs = UserBinPaths.candidateDirs("/home/u", false, null);
        assertEquals(List.of(
                "/home/u" + sep + ".local" + sep + "bin",
                "/home/u" + sep + ".local" + sep + "share" + sep + "fnm",
                "/home/u" + sep + ".nvm"), dirs);
    }

    @Test
    void windowsCandidateDirsUseAppData() {
        List<String> dirs = UserBinPaths.candidateDirs("C:\\u", true, "D:\\AppData");
        assertTrue(dirs.contains("D:\\AppData\\npm"), String.valueOf(dirs));
    }

    @Test
    void mutationPreservesOtherEnvVars() throws Exception {
        Files.createDirectories(home.resolve(".local").resolve("bin"));
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin", "HOME_VAR", "keep"));
        UserBinPaths.applyTo(env, home.toString(), false);
        assertEquals("keep", env.get("HOME_VAR"));
    }
}
