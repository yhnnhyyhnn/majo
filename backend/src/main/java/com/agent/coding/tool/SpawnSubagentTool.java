package com.agent.coding.tool;

import com.agent.coding.subagent.SubagentService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spawn an ephemeral sub-task agent in the current workspace and wait for
 * its final result (QwenPaw spawn_subagent equivalent).
 */
@Component
public class SpawnSubagentTool {

    private final SubagentService subagentService;

    public SpawnSubagentTool(SubagentService subagentService) {
        this.subagentService = subagentService;
    }

    @Tool(name = "spawn_subagent",
            description = "在当前工作区生成一个临时子任务 Agent 并执行，返回子任务结果")
    public String spawnSubagent(
        @ToolParam(name = "task", description = "子任务描述") String task
    ) {
        if (task == null || task.isBlank()) {
            return "错误: task 不能为空";
        }
        return subagentService.spawn(task);
    }
}
