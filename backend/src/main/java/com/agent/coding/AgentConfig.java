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
    static final Map<String, String> TOOL_NAME_MAP = Map.ofEntries(
        Map.entry("read_file", "read_file"),
        Map.entry("write_file", "write_file"),
        Map.entry("edit_file", "edit_file"),
        Map.entry("append_file", "append_file"),
        Map.entry("execute_command", "execute_shell_command"),
        Map.entry("search_code", "grep_search"),
        Map.entry("list_directory", "glob_search"),
        Map.entry("find_symbol", "ast_search"),
        Map.entry("get_current_time", "get_current_time"),
        Map.entry("set_user_timezone", "set_user_timezone"),
        Map.entry("web_fetch", "web_fetch"),
        Map.entry("web_search", "web_search"),
        Map.entry("get_token_usage", "get_token_usage"),
        Map.entry("send_file_to_user", "send_file_to_user"),
        Map.entry("spawn_subagent", "spawn_subagent"),
        Map.entry("chat_with_agent", "chat_with_agent"),
        Map.entry("submit_to_agent", "submit_to_agent"),
        Map.entry("check_agent_task", "check_agent_task"),
        Map.entry("view_image", "view_image"),
        Map.entry("view_video", "view_video"),
        Map.entry("desktop_screenshot", "desktop_screenshot")
    );

    @Bean
    Set<String> implementedMajoToolNames() {
        return TOOL_NAME_MAP.values().stream().collect(Collectors.toUnmodifiableSet());
    }

    @Bean
    Toolkit toolkit(ReadFileTool readFile, WriteFileTool writeFile,
            EditFileTool editFile, AppendFileTool appendFile,
            ExecuteCommandTool exec, SearchCodeTool search,
            ListDirectoryTool listDir, FindSymbolTool findSymbol, GitTools git,
            GetCurrentTimeTool getCurrentTime, SetUserTimezoneTool setUserTimezone,
            WebFetchTool webFetch, WebSearchTool webSearch,
            TokenUsageTool tokenUsage, SendFileToUserTool sendFileToUser,
            SpawnSubagentTool spawnSubagent, ChatWithAgentTool chatWithAgent,
            SubmitToAgentTool submitToAgent, CheckAgentTaskTool checkAgentTask,
            ViewImageTool viewImage, ViewVideoTool viewVideo,
            DesktopScreenshotTool desktopScreenshot) {
        var t = new Toolkit();
        t.registerTool(readFile);
        t.registerTool(writeFile);
        t.registerTool(editFile);
        t.registerTool(appendFile);
        t.registerTool(exec);
        t.registerTool(search);
        t.registerTool(listDir);
        t.registerTool(findSymbol);
        t.registerTool(git);
        t.registerTool(getCurrentTime);
        t.registerTool(setUserTimezone);
        t.registerTool(webFetch);
        t.registerTool(webSearch);
        t.registerTool(tokenUsage);
        t.registerTool(sendFileToUser);
        t.registerTool(spawnSubagent);
        t.registerTool(chatWithAgent);
        t.registerTool(submitToAgent);
        t.registerTool(checkAgentTask);
        t.registerTool(viewImage);
        t.registerTool(viewVideo);
        t.registerTool(desktopScreenshot);
        return t;
    }
}
