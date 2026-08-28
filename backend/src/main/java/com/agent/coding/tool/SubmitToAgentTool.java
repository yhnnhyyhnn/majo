package com.agent.coding.tool;

import com.agent.coding.subagent.SubagentService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Submit a background task to another configured agent and return the task
 * id immediately (QwenPaw submit_to_agent equivalent). Poll with
 * check_agent_task.
 */
@Component
public class SubmitToAgentTool {

    private final SubagentService subagentService;

    public SubmitToAgentTool(SubagentService subagentService) {
        this.subagentService = subagentService;
    }

    @Tool(name = "submit_to_agent",
            description = "向另一个配置好的 Agent 提交后台任务，立即返回 task_id")
    public String submitToAgent(
        @ToolParam(name = "agent_name", description = "目标 Agent 的 id") String agentName,
        @ToolParam(name = "task", description = "后台任务描述") String task
    ) {
        if (task == null || task.isBlank()) {
            return "错误: task 不能为空";
        }
        String taskId = subagentService.submit(agentName, task);
        return "任务已提交，task_id: " + taskId + "。可使用 check_agent_task 查询进度。";
    }
}
