package com.agent.coding;

import com.agent.coding.tool.*;
import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class AgentConfig {

    /**
     * SDK-registered tool name → Majo API name mapping.
     * Add new tools here and to the Toolkit bean below.
     */
    static final Map<String, String> TOOL_NAME_MAP = Map.of(
        "read_file", "read_file",
        "write_file", "write_file",
        "edit_file", "edit_file",
        "execute_command", "execute_shell_command",
        "search_code", "grep_search",
        "list_directory", "glob_search",
        "find_symbol", "ast_search"
    );

    @Bean
    Set<String> implementedMajoToolNames() {
        return TOOL_NAME_MAP.values().stream().collect(Collectors.toUnmodifiableSet());
    }

    @Bean
    Toolkit toolkit(ReadFileTool readFile, WriteFileTool writeFile,
            EditFileTool editFile, ExecuteCommandTool exec,
            SearchCodeTool search, ListDirectoryTool listDir,
            FindSymbolTool findSymbol, GitTools git) {
        var t = new Toolkit();
        t.registerTool(readFile);
        t.registerTool(writeFile);
        t.registerTool(editFile);
        t.registerTool(exec);
        t.registerTool(search);
        t.registerTool(listDir);
        t.registerTool(findSymbol);
        t.registerTool(git);
        return t;
    }
}
