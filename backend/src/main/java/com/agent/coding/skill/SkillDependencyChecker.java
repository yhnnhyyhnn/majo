package com.agent.coding.skill;

import com.agent.coding.mcp.McpStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runtime validation of a skill's declared prerequisites
 * ({@code requires.bins / env / mcp}). Ported from QwenPaw
 * registry.check_skill_dependencies (#7609): returns the unmet
 * prerequisites without logging or starting processes.
 *
 * <p>Binary lookup is a {@code shutil.which} equivalent. MCP requirements
 * are checked against the global MCP client cards (majo keeps MCP config
 * global, not per-workspace drivers like QwenPaw).
 */
public final class SkillDependencyChecker {

    private SkillDependencyChecker() {}

    /**
     * Return the unmet prerequisites for the requirements.
     *
     * @param requirements parsed skill requirements
     * @param env          environment to check against (usually System.getenv())
     * @param workspaceDir agent workspace (reserved; MCP cards are global in majo)
     */
    public static List<String> check(SkillStore.SkillRequirements requirements,
                                     Map<String, String> env,
                                     Path workspaceDir) {
        List<String> missing = new ArrayList<>();
        if (requirements == null) {
            return missing;
        }
        for (String envName : requirements.requireEnvs()) {
            if (isBlank(env.get(envKey(envName)))) {
                missing.add("Environment variable not set: " + envName);
            }
        }
        for (String binary : requirements.requireBins()) {
            if (which(binary) == null) {
                missing.add("CLI binary not found on PATH: " + binary);
            }
        }
        for (String mcpName : requirements.requireMcps()) {
            String problem = mcpProblem(mcpName);
            if (problem != null) {
                missing.add(problem);
            }
        }
        return missing;
    }

    /** Windows resolves env names case-insensitively (registry semantics). */
    static String envKey(String key) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows ? key.toUpperCase(Locale.ROOT) : key;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Locate an executable on PATH, mirroring shutil.which behaviour
     * (direct absolute/relative hits and Windows PATHEXT-ish candidates).
     *
     * @return the resolved path, or null when not found
     */
    public static String which(String binary) {
        if (binary == null || binary.isBlank()) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path direct = Path.of(binary);
        if (direct.isAbsolute() || direct.getParent() != null) {
            return isExecutableFile(direct) ? binary : null;
        }
        String pathEnv = System.getenv(envKey("PATH"));
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        names.add(binary);
        if (windows) {
            for (String ext : List.of(".com", ".exe", ".bat", ".cmd")) {
                names.add(binary + ext);
            }
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(dir.trim()).resolve(name);
                if (isExecutableFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    private static boolean isExecutableFile(Path p) {
        return Files.isRegularFile(p) && Files.isExecutable(p);
    }

    /**
     * Check a required MCP server against the global client cards.
     *
     * @return null when configured and enabled, otherwise the problem message
     */
    private static String mcpProblem(String name) {
        try (Stream<Path> cards = Files.list(McpStore.cardsDir())) {
            for (Path file : (Iterable<Path>) cards.filter(p -> p.getFileName().toString().endsWith(".json"))::iterator) {
                Map<String, Object> card = McpStore.loadCardOrNull(
                        file.getFileName().toString().replace(".json", ""));
                if (card == null) {
                    continue;
                }
                if (!name.equals(String.valueOf(card.get("name")))) {
                    continue;
                }
                if (!"mcp".equals(String.valueOf(card.get("protocol")))) {
                    return "MCP server configuration is invalid: " + name;
                }
                if (!com.agent.coding.skill.SkillService.bool(card.get("enabled"), true)) {
                    return "MCP server is disabled: " + name;
                }
                return null; // found, valid and enabled
            }
        } catch (IOException ignored) {
            // cards dir missing/unreadable → not configured
        }
        return "MCP server not configured: " + name;
    }
}
