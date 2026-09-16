package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Process hardening checks for execute_command (ADR-0012 phase 1): output
 * capture, and — the regression this ADR fixes — a timeout that actually
 * fires even when the child keeps its stdout open, returning promptly
 * instead of hanging.
 */
class ExecuteCommandToolTest {

    @TempDir
    Path workspace;

    private final ExecuteCommandTool tool = new ExecuteCommandTool();

    @AfterEach
    void cleanUp() {
        WorkspaceContext.clear();
    }

    @Test
    void capturesOutputAndExitStatus() {
        WorkspaceContext.set(workspace.toString());
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String out = windows
                ? tool.executeCommand("echo hello-from-majo", 15)
                : tool.executeCommand("echo hello-from-majo", 15);
        assertTrue(out.contains("hello-from-majo"), out);
        assertTrue(out.startsWith("[成功]") || out.startsWith("[警告]"), out);
    }

    @Test
    @Timeout(20)
    void timeoutFiresAndReturnsPromptly() {
        WorkspaceContext.set(workspace.toString());
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        // Sleeps well past the timeout while keeping the pipe open — the
        // old implementation would block on readLine and never time out.
        String cmd = windows ? "ping -n 60 127.0.0.1" : "sleep 60";
        long start = System.currentTimeMillis();
        String out = tool.executeCommand(cmd, 2);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(out.startsWith("[超时]"), out);
        assertTrue(elapsed < 15_000, "timeout returned in " + elapsed + "ms");
    }
}
