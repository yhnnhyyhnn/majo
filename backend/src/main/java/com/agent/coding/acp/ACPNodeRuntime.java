package com.agent.coding.acp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ACP Node runtime detection, ported from qwenpaw/agents/acp/node_runtime.py.
 * Builds the candidate list (bundled / system / custom) and resolves the
 * effective node path used by ACP subprocesses.
 */
public final class ACPNodeRuntime {

    private static final String DESKTOP_NODE_RUNTIME_ENV = "QWENPAW_DESKTOP_NODE_RUNTIME";

    private ACPNodeRuntime() {
    }

    public static Map<String, Object> getNodeRuntimeStatus(String nodePath) {
        String configured = nodePath == null ? "" : nodePath.trim();
        List<Map<String, Object>> candidates = new ArrayList<>();

        String bundled = bundledNodePath();
        if (bundled != null) {
            candidates.add(resolveNodeRuntime(bundled, "bundled", "bundled"));
        }

        String systemNode = which("node");
        if (systemNode != null) {
            candidates.add(resolveNodeRuntime(systemNode, "system", "system"));
        } else {
            candidates.add(missingCandidate("system", "system",
                "system_node_missing", "System Node was not detected"));
        }

        if (!configured.isEmpty() && !samePath(configured, candidates)) {
            candidates.add(resolveNodeRuntime(configured, "custom", "custom"));
        }

        String effective = effectiveNodePath(configured, candidates);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_path", configured);
        result.put("effective_node_path", effective);
        result.put("candidates", candidates);
        return result;
    }

    public static Map<String, Object> resolveNodeRuntime(String nodePath, String key, String label) {
        Path node = normalizeNodePath(nodePath);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("key", key);
        candidate.put("label", label);
        candidate.put("node_path", node.toString());
        candidate.put("npx_path", "");
        candidate.put("node_version", "");
        candidate.put("npx_version", "");
        candidate.put("available", false);
        candidate.put("reason_code", "");
        candidate.put("reason", "");

        if (!Files.isRegularFile(node)) {
            candidate.put("reason_code", "node_missing");
            candidate.put("reason", "Node path does not exist");
            return candidate;
        }

        Path nodeDir = node.getParent() == null ? Path.of(".") : node.getParent();
        Map<String, String> env = prependPath(nodeDir);
        String[] version = version(node.toString(), env);
        String nodeVersion = version[0];
        String error = version[1];
        if (!error.isEmpty()) {
            candidate.put("reason_code", "version_check_failed");
            candidate.put("reason", error);
            return candidate;
        }

        Path npx = npxPath(node);
        if (npx == null) {
            candidate.put("node_version", nodeVersion);
            candidate.put("reason_code", "npx_missing");
            candidate.put("reason", "npx was not found");
            return candidate;
        }

        String[] npxVersionResult = version(npx.toString(), env);
        if (!npxVersionResult[1].isEmpty()) {
            candidate.put("node_version", nodeVersion);
            candidate.put("npx_path", npx.toString());
            candidate.put("reason_code", "version_check_failed");
            candidate.put("reason", npxVersionResult[1]);
            return candidate;
        }

        candidate.put("node_version", nodeVersion);
        candidate.put("npx_path", npx.toString());
        candidate.put("npx_version", npxVersionResult[0]);
        candidate.put("available", true);
        return candidate;
    }

    public static Map<String, Object> resolveNodeRuntime(String nodePath) {
        return resolveNodeRuntime(nodePath, "custom", "custom");
    }

    private static Map<String, Object> missingCandidate(String key, String label,
                                                        String reasonCode, String reason) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("key", key);
        candidate.put("label", label);
        candidate.put("node_path", "");
        candidate.put("npx_path", "");
        candidate.put("node_version", "");
        candidate.put("npx_version", "");
        candidate.put("available", false);
        candidate.put("reason_code", reasonCode);
        candidate.put("reason", reason);
        return candidate;
    }

    private static String effectiveNodePath(String configured, List<Map<String, Object>> candidates) {
        if (!configured.isEmpty()) {
            for (Map<String, Object> candidate : candidates) {
                if (Boolean.TRUE.equals(candidate.get("available"))
                        && samePath(configured, String.valueOf(candidate.get("node_path")))) {
                    return String.valueOf(candidate.get("node_path"));
                }
            }
        }
        for (String key : new String[]{"bundled", "system"}) {
            for (Map<String, Object> candidate : candidates) {
                if (key.equals(candidate.get("key")) && Boolean.TRUE.equals(candidate.get("available"))) {
                    return String.valueOf(candidate.get("node_path"));
                }
            }
        }
        return "";
    }

    private static String bundledNodePath() {
        String root = System.getenv(DESKTOP_NODE_RUNTIME_ENV);
        if (root == null || root.isBlank()) {
            return null;
        }
        return normalizeNodePath(stripWindowsExtendedPrefix(root.trim())).toString();
    }

    private static Path normalizeNodePath(String value) {
        String expanded = expandEnv(value.trim());
        Path path = Path.of(expanded).toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            path = path.resolve(isWindows() ? "node.exe" : "bin/node");
        }
        return path;
    }

    private static Path npxPath(Path node) {
        String[] names = isWindows() ? new String[]{"npx.cmd", "npx.exe"} : new String[]{"npx"};
        Path nodeDir = node.getParent() == null ? Path.of(".") : node.getParent();
        for (String name : names) {
            Path candidate = nodeDir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        String found = which("npx", nodeDir.toString());
        return found == null ? null : Path.of(found).toAbsolutePath().normalize();
    }

    private static String[] version(String executable, Map<String, String> env) {
        try {
            ProcessBuilder pb = new ProcessBuilder(executable, "--version");
            pb.environment().clear();
            pb.environment().putAll(env);
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new String[]{"", "version check failed: timeout"};
            }
            String out = readAll(process.getInputStream());
            String err = readAll(process.getErrorStream());
            if (process.exitValue() != 0) {
                String msg = (err == null || err.isBlank()) ? out : err;
                return new String[]{"", (msg == null || msg.isBlank())
                    ? "version check failed" : msg.trim()};
            }
            String value = (out == null || out.isBlank()) ? err : out;
            return new String[]{value == null ? "" : value.trim(), ""};
        } catch (Exception e) {
            return new String[]{"", Path.of(executable).getFileName() + " --version failed: " + e.getMessage()};
        }
    }

    private static String readAll(java.io.InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> prependPath(Path path) {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        String key = pathEnvKey(env);
        String existing = env.getOrDefault(key, "");
        env.put(key, existing == null || existing.isEmpty()
            ? path.toString() : path + File.pathSeparator + existing);
        return env;
    }

    private static String pathEnvKey(Map<String, String> env) {
        for (String name : env.keySet()) {
            if (name.equalsIgnoreCase("path")) {
                return name;
            }
        }
        return isWindows() ? "Path" : "PATH";
    }

    private static boolean samePath(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        return normalizeCase(Path.of(left).toAbsolutePath().normalize())
            .equals(normalizeCase(Path.of(right).toAbsolutePath().normalize()));
    }

    private static boolean samePath(String configured, List<Map<String, Object>> candidates) {
        for (Map<String, Object> candidate : candidates) {
            String path = String.valueOf(candidate.get("node_path"));
            if (!path.isEmpty() && samePath(configured, path)) {
                return true;
            }
        }
        return false;
    }

    private static Path normalizeCase(Path path) {
        return isWindows() ? Path.of(path.toString().toLowerCase()) : path;
    }

    private static String stripWindowsExtendedPrefix(String value) {
        if (!isWindows()) {
            return value;
        }
        if (value.startsWith("\\\\?\\UNC\\")) {
            return "\\\\" + value.substring(8);
        }
        if (value.startsWith("\\\\?\\")) {
            return value.substring(4);
        }
        return value;
    }

    private static String expandEnv(String value) {
        String result = value;
        int idx;
        while ((idx = result.indexOf('%')) != -1) {
            int end = result.indexOf('%', idx + 1);
            if (end == -1) {
                break;
            }
            String name = result.substring(idx + 1, end);
            String replacement = System.getenv(name);
            if (replacement == null) {
                break;
            }
            result = result.substring(0, idx) + replacement + result.substring(end + 1);
        }
        return result;
    }

    private static String which(String name) {
        return which(name, null);
    }

    private static String which(String name, String extraPath) {
        String pathEnv = System.getenv(pathEnvKey(System.getenv()));
        String[] dirs = pathEnv == null ? new String[0]
            : pathEnv.split(java.util.regex.Pattern.quote(File.pathSeparator));
        List<Path> candidates = new ArrayList<>();
        if (extraPath != null && !extraPath.isBlank()) {
            candidates.add(Path.of(extraPath));
        }
        for (String dir : dirs) {
            if (dir != null && !dir.isBlank()) {
                candidates.add(Path.of(dir));
            }
        }
        for (Path dir : candidates) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
            if (isWindows()) {
                for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                    Path withExt = dir.resolve(name + ext);
                    if (Files.isRegularFile(withExt)) {
                        return withExt.toAbsolutePath().normalize().toString();
                    }
                }
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }
}
