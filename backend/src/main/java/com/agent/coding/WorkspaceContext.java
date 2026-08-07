package com.agent.coding;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Thread-local workspace context for tools.
 * Set by ChatController before streaming; tools resolve paths relative to this.
 */
public class WorkspaceContext {

    private static final ThreadLocal<Path> WORKSPACE = new ThreadLocal<>();

    public static void set(String workspace) {
        if (workspace != null && !workspace.isBlank()) {
            WORKSPACE.set(Paths.get(workspace).toAbsolutePath().normalize());
        }
    }

    public static Path get() {
        Path ws = WORKSPACE.get();
return ws != null ? ws : com.agent.coding.skill.SkillStore.WORKING_DIR;
    }

    public static void clear() {
        WORKSPACE.remove();
    }
}
