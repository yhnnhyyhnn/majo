package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@Component
public class ExecuteCommandTool {
    @Tool(name = "execute_command", description = "执行 shell 命令，默认60s超时")
    public String executeCommand(
        @ToolParam(name = "command", description = "命令") String command,
        @ToolParam(name = "timeoutSeconds", description = "超时秒数(可选)") Integer timeoutSeconds
    ) {
        int timeout = timeoutSeconds != null ? Math.min(timeoutSeconds, 120) : 60;
        try {
            var os = System.getProperty("os.name").toLowerCase();
            var pb = os.contains("win")
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
            pb.directory(WorkspaceContext.get().toFile());
            pb.redirectErrorStream(true);
            var p = pb.start();
            var sb = new StringBuilder();
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append("\n");
                    if (sb.length() > 50000) { p.destroyForcibly(); sb.append("..."); break; }
                }
            }
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) { p.destroyForcibly(); return "[超时]"; }
            return (p.exitValue() == 0 ? "[成功] " : "[警告] exit=" + p.exitValue() + " ") + sb;
        } catch (Exception e) {
            return "[错误] " + e.getMessage();
        }
    }
}
