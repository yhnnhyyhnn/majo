package com.agent.coding.tool;

import com.agent.coding.subagent.SubagentService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Check the status/result of a background agent task (QwenPaw
 * check_agent_task equivalent).
 */
@Component
public class CheckAgentTaskTool {

    private final SubagentService subagentService;

    public CheckAgentTaskTool(SubagentService subagentService) {
        this.subagentService = subagentService;
    }

    @Tool(name = "check_agent_task",
            description = "查询后台 Agent 任务的状态和结果")
    public String checkAgentTask(
        @ToolParam(name = "task_id", description = "submit_to_agent 返回的 task_id") String taskId
    ) {
        if (taskId == null || taskId.isBlank()) {
            return "错误: task_id 不能为空";
        }
        Map<String, Object> info = subagentService.check(taskId);
        String status = String.valueOf(info.get("status"));
        String result = info.get("result") == null ? "" : String.valueOf(info.get("result"));
        String error = info.get("error") == null ? "" : String.valueOf(info.get("error"));
        return switch (status) {
            case "completed" -> "任务 " + taskId + " 已完成。\n结果: " + result;
            case "failed" -> "任务 " + taskId + " 失败: " + error;
            case "cancelled" -> "任务 " + taskId + " 已取消";
            case "running" -> "任务 " + taskId + " 运行中，请稍后再查";
            default -> "任务 " + taskId + " 不存在或已过期";
        };
    }
}
