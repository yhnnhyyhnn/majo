package com.agent.coding;

import com.agent.coding.tool.*;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

@Configuration
public class AgentConfig {

    @Bean
    OpenAIChatModel chatModel(AgentProperties props) {
        return OpenAIChatModel.builder()
            .apiKey(props.getApiKey())
            .baseUrl(props.getBaseUrl())
            .modelName(props.getModelName())
            .build();
    }

    @Bean
    HarnessAgent codingAgent(OpenAIChatModel model, Toolkit toolkit) {
        return HarnessAgent.builder()
            .name("majo")
            .sysPrompt("你是一个专业的编码助手。工具包括: read_file/write_file/edit_file(读写编辑), search_code/find_symbol/list_directory(搜索), execute_command(执行命令), git_status/git_diff/git_branch/git_commit/git_add/git_log(Git操作)。回答简洁专业。")
            .model(model)
            .toolkit(toolkit)
            .workspace(Paths.get(System.getProperty("user.dir")))
            .build();
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
