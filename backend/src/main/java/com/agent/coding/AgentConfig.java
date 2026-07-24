package com.agent.coding;

import com.agent.coding.tool.*;
import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

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
