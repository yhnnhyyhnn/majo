package com.agent.coding.security;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File Guard — blocks agent file tools from touching protected paths or
 * escaping the workspace root. Configuration lives in
 * {@code agents.json security.file_guard} ({@code enabled}, {@code paths}),
 * shared with the /api/config/security/file-guard endpoints.
 */
@Service
public class FileGuardService {

    private static final Logger log = LoggerFactory.getLogger(FileGuardService.class);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fileGuardSection() {
        Map<String, Object> config = AgentStore.loadConfig();
        Object sec = config.get("security");
        if (sec instanceof Map<?, ?> m) {
            Object fg = m.get("file_guard");
            if (fg instanceof Map<?, ?> fgm) {
                return new LinkedHashMap<>((Map<String, Object>) fgm);
            }
        }
        return new LinkedHashMap<>();
    }

    /**
     * Check a file-tool call.
     *
     * @param toolName     the agent tool name (read_file / write_file / ...)
     * @param input        tool parameters (expects a {@code path})
     * @param workspaceRoot current working directory for the agent turn
     * @return block reason, or {@code null} to allow
     */
    public String check(String toolName, Map<String, Object> input, Path workspaceRoot) {
        return check(toolName, input, workspaceRoot, fileGuardSection());
    }

    /**
     * Check a file-tool call against an explicit File Guard policy map
     * (testable without touching agents.json).
     *
     * @param toolName     the agent tool name (read_file / write_file / ...)
     * @param input        tool parameters (expects a {@code path})
     * @param workspaceRoot current working directory for the agent turn
     * @param cfg          the file_guard config map
     * @return block reason, or {@code null} to allow
     */
    public String check(String toolName, Map<String, Object> input, Path workspaceRoot,
                        Map<String, Object> cfg) {
        if (!ToolGuardService.FILE_TOOLS.contains(toolName)) {
            return null;
        }
        Object pathObj = input == null ? null : input.get("path");
        if (pathObj == null || String.valueOf(pathObj).isBlank()) {
            return null;
        }
        String rawPath = String.valueOf(pathObj);

        boolean enabled = SkillService.bool(cfg.get("enabled"), true);
        if (!enabled) {
            return null;
        }

        Path root = workspaceRoot != null ? workspaceRoot.toAbsolutePath().normalize()
                : Paths.get("").toAbsolutePath().normalize();

        // 1) Workspace containment
        Path resolved;
        try {
            resolved = root.resolve(rawPath).normalize();
        } catch (Exception e) {
            return "File Guard: invalid path '" + rawPath + "'";
        }
        if (!resolved.startsWith(root)) {
            log.warn("[file-guard] path escapes workspace: {} (root {})", rawPath, root);
            return "File Guard: path escapes the workspace root: " + rawPath;
        }

        // 2) Explicit protected paths (absolute, or relative to the workspace)
        Object paths = cfg.get("paths");
        if (paths instanceof List<?> list) {
            for (Object p : list) {
                if (p == null || String.valueOf(p).isBlank()) {
                    continue;
                }
                Path protectedPath;
                try {
                    protectedPath = Paths.get(String.valueOf(p)).toAbsolutePath().normalize();
                } catch (Exception e) {
                    protectedPath = root.resolve(String.valueOf(p)).normalize();
                }
                if (resolved.startsWith(protectedPath) || protectedPath.startsWith(resolved)) {
                    log.warn("[file-guard] protected path '{}' blocked: {}", p, rawPath);
                    return "File Guard: path is protected: " + rawPath;
                }
            }
        }
        return null;
    }

    /** Protected-path list for the /api/config/security/file-guard endpoint. */
    public List<String> protectedPaths() {
        List<String> result = new ArrayList<>();
        Object paths = fileGuardSection().get("paths");
        if (paths instanceof List<?> list) {
            for (Object p : list) {
                result.add(String.valueOf(p));
            }
        }
        return result;
    }
}
