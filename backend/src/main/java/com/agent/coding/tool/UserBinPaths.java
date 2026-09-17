package com.agent.coding.tool;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Prepends standard user-level bin directories onto a subprocess PATH
 * (QwenPaw #7057 counterpart). When majo runs under a service manager or a
 * stripped environment, the inherited PATH often omits the directories where
 * single-user toolchains install their CLIs — so shell commands, delegated
 * ACP runners and language servers can't find them:
 *
 * <ul>
 *   <li>Unix: {@code ~/.local/bin} (gh, cmake, ast-grep…), fnm / nvm bases;</li>
 *   <li>Windows: {@code %APPDATA%\npm} (global npm shims) and per-user
 *       Python dirs under {@code %LOCALAPPDATA%\Programs\Python}.</li>
 * </ul>
 *
 * Only directories that exist on disk are added, duplicates against the
 * existing PATH are skipped, and the existing PATH is kept intact after the
 * additions.
 */
public final class UserBinPaths {

    private UserBinPaths() {
    }

    /** Mutate {@code env} in place: PATH gains user bin dirs at the front. */
    public static void applyTo(Map<String, String> env) {
        applyTo(env, System.getProperty("user.home"),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    /** Testable variant with explicit home and platform branch. */
    public static void applyTo(Map<String, String> env, String home, boolean windows) {
        // Locate the PATH-like key without creating duplicates.
        String pathKey = "PATH";
        for (String key : env.keySet()) {
            if (key.equalsIgnoreCase("path")) {
                pathKey = key;
                break;
            }
        }
        String existing = env.getOrDefault(pathKey, "");

        Set<String> seen = new LinkedHashSet<>();
        for (String p : existing.split(java.io.File.pathSeparator)) {
            if (!p.isBlank()) {
                seen.add(normalize(p));
            }
        }

        List<String> additions = new ArrayList<>();
        for (String dir : candidateDirs(home, windows)) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            String norm = normalize(dir);
            if (norm.isEmpty() || seen.contains(norm)) {
                continue;
            }
            if (!new File(dir).isDirectory()) {
                continue;
            }
            seen.add(norm);
            additions.add(dir);
        }
        if (additions.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>(additions);
        if (!existing.isBlank()) {
            parts.add(existing);
        }
        env.put(pathKey, String.join(File.pathSeparator, parts));
    }

    private static List<String> candidateDirs(String home, boolean windows) {
        return candidateDirs(home, windows, System.getenv("APPDATA"));
    }

    static List<String> candidateDirs(String home, boolean windows, String appData) {
        List<String> candidates = new ArrayList<>();
        if (windows) {
            if (appData != null && !appData.isBlank()) {
                candidates.add(appData + File.separator + "npm");
            }
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                File pythonBase = new File(localAppData, "Programs" + File.separator + "Python");
                File[] versions = pythonBase.listFiles(File::isDirectory);
                if (versions != null) {
                    for (File v : versions) {
                        candidates.add(v.getAbsolutePath());
                        candidates.add(new File(v, "Scripts").getAbsolutePath());
                    }
                }
            }
        } else {
            candidates.add(home + File.separator + ".local" + File.separator + "bin");
            candidates.add(home + File.separator + ".local" + File.separator + "share"
                    + File.separator + "fnm");
            candidates.add(home + File.separator + ".nvm");
        }
        return candidates;
    }

    private static String normalize(String path) {
        String n = path.replace("\\", "/").toLowerCase(Locale.ROOT);
        while (n.endsWith("/") && n.length() > 1) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }
}
