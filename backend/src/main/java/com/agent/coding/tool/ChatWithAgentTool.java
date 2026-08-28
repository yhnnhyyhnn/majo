package com.agent.coding.tool;

import com.agent.coding.subagent.SubagentService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Send a message to another configured agent and wait for its reply
 * (QwenPaw chat_with_agent equivalent).
 */
@Component
public class ChatWithAgentTool {

    private final SubagentService subagentService;

    public ChatWithAgentTool(SubagentService subagentService) {
        this.subagentService = subagentService;
    }

    @Tool(name = "chat_with_agent",
            description = "向另一个配置好的 Agent 发送消息并等待回复")
    public String chatWithAgent(
        @ToolParam(name = "agent_name", description = "目标 Agent 的 id") String agentName,
        @ToolParam(name = "message", description = "要发送的消息") String message
    ) {
        if (message == null || message.isBlank()) {
            return "错误: message 不能为空";
        }
        return subagentService.chatWithAgent(agentName, message);
    }
}
