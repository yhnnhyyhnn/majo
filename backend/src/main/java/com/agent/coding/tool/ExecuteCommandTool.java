package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.security.ShellNormalization;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Execute a shell command inside the workspace (ADR-0012 phase 1: process
 * hardening — no OS sandbox yet, see that ADR for the roadmap).
 *
 * <p>Timeout is enforced for real: stdout is consumed on a daemon thread so
 * a child that never closes its pipe cannot outlive {@code waitFor(timeout)}
 * (the previous read-loop-first implementation could hang forever). On
 * timeout, output-cap or exit, the whole process tree is destroyed — on
 * Windows {@code cmd.exe /c} grandchildren otherwise survive a plain
 * {@code destroyForcibly} of the direct child.
 */
@Component
public class ExecuteCommandTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    @Tool(name = "execute_command", description = "执行 shell 命令，默认60s超时")
    public String executeCommand(
        @ToolParam(name = "command", description = "命令") String command,
        @ToolParam(name = "timeoutSeconds", description = "超时秒数(可选)") Integer timeoutSeconds
    ) {
        int timeout = timeoutSeconds != null
                ? Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS) : DEFAULT_TIMEOUT_SECONDS;
        var os = System.getProperty("os.name").toLowerCase();
        // POSIX shells drop backslash-newline continuations before parsing;
        // execute the same spelling the Tool Guard checks saw.
        if (!os.contains("win")) {
            command = ShellNormalization.normalizePosixLineContinuations(command);
        }
        Process p = null;
        try {
            var pb = os.contains("win")
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
            pb.directory(WorkspaceContext.get().toFile());
            // Daemon-friendly PATH: user-installed CLIs (gh, ast-grep, npm
            // shims) stay reachable under stripped service environments.
            UserBinPaths.applyTo(pb.environment());
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process proc = p;

            // Consume stdout off-thread so the pipe cannot block the timeout.
            var output = new StringBuilder();
            Thread pump = new Thread(() -> {
                try (var r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() > MAX_OUTPUT_CHARS) {
                                return;
                            }
                            output.append(line).append("\n");
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "exec-pump");
            pump.setDaemon(true);
            pump.start();

            boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                destroyTree(p);
                pump.join(1000);
                return "[超时] 命令超过 " + timeout + "s 未完成，已终止进程树。部分输出:\n" + snapshot(output);
            }
            boolean capped;
            synchronized (output) {
                capped = output.length() > MAX_OUTPUT_CHARS;
            }
            if (capped) {
                destroyTree(p);
                return "[输出超限] 输出超过 " + MAX_OUTPUT_CHARS + " 字符，已截断:\n" + snapshot(output);
            }
            pump.join(1000);
            return (p.exitValue() == 0 ? "[成功] " : "[警告] exit=" + p.exitValue() + " ")
                    + snapshot(output);
        } catch (Exception e) {
            if (p != null) {
                destroyTree(p);
            }
            return "[错误] " + e.getMessage();
        }
    }

    /** Kill the process and everything it spawned. */
    private static void destroyTree(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception ignored) {
        }
        p.destroyForcibly();
    }

    private static String snapshot(StringBuilder output) {
        synchronized (output) {
            String s = output.toString();
            return s.length() > MAX_OUTPUT_CHARS ? s.substring(0, MAX_OUTPUT_CHARS) + "…" : s;
        }
    }
}
