package com.agent.coding.subagent;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.agent.AgentStore;
import com.agent.coding.security.ToolGuardHook;
import com.agent.coding.service.ModelRoutingService;
import com.agent.coding.service.ModelRoutingService.ModelSlot;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-contained subagent execution for the spawn_subagent / chat_with_agent
 * / submit_to_agent / check_agent_task tools.
 *
 * <p>Each sub-task builds its own {@link HarnessAgent} (same model routing
 * and toolkit as the parent, own workspace, own session) and runs an
 * independent agent loop — no dependency on the harness's internal subagent
 * middleware, which is bound to the parent agent's runtime.
 */
@Service
public class SubagentService {

    private static final Logger log = LoggerFactory.getLogger(SubagentService.class);

    private static final String SYS_PROMPT = "你是一个执行子任务的编码助手。请使用工具完成任务，最后简洁地总结结果。"
            + "工具包括: read_file/write_file/edit_file/append_file(文件), "
            + "search_code/find_symbol/list_directory(搜索), execute_command(命令), "
            + "git_status/git_diff/git_branch/git_commit/git_add/git_log(Git)。";

    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_RESULT_CHARS = 16_000;

    private final ModelRoutingService modelRouting;
    private final Toolkit toolkit;
    private final ToolGuardHook toolGuardHook;
    private final SubagentTaskRegistry registry;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "majo-subagent");
        t.setDaemon(true);
        return t;
    });

    public SubagentService(ModelRoutingService modelRouting,
                           @Lazy Toolkit toolkit,
                           ToolGuardHook toolGuardHook,
                           SubagentTaskRegistry registry) {
        this.modelRouting = modelRouting;
        this.toolkit = toolkit;
        this.toolGuardHook = toolGuardHook;
        this.registry = registry;
    }

    // ── Synchronous tools ────────────────────────────────────────────

    /** spawn_subagent: run an ephemeral sub-task in the current workspace. */
    public String spawn(String task) {
        Path workspace = WorkspaceContext.get();
        Path subDir;
        try {
            subDir = workspace.resolve(".subagents").resolve(
                    UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            Files.createDirectories(subDir);
        } catch (Exception e) {
            return "错误: 无法创建子任务工作区: " + e.getMessage();
        }
        HarnessAgent agent = buildAgent("subagent", subDir, null);
        if (agent == null) {
            return "错误: 未配置有效的活动模型，无法启动子任务";
        }
        try {
            return runAgent(agent, task, "subagent-" + UUID.randomUUID());
        } catch (Exception e) {
            log.warn("[subagent] spawn failed", e);
            return "子任务执行失败: " + e.getMessage();
        }
    }

    /** chat_with_agent: send a message to another configured agent. */
    public String chatWithAgent(String agentId, String message) {
        if (agentId == null || agentId.isBlank()) {
            return "错误: agent_name 不能为空";
        }
        Path workspace;
        try {
            workspace = AgentStore.workspaceDirForAgent(agentId);
        } catch (Exception e) {
            return "错误: 找不到 Agent '" + agentId + "'";
        }
        HarnessAgent agent = buildAgent(agentId, workspace, agentId);
        if (agent == null) {
            return "错误: Agent '" + agentId + "' 未配置有效的活动模型";
        }
        try {
            String result = runAgent(agent, message, "chat-" + UUID.randomUUID());
            return result.isBlank() ? "Agent '" + agentId + "' 无回复" : result;
        } catch (Exception e) {
            log.warn("[subagent] chat_with_agent failed", e);
            return "与 Agent '" + agentId + "' 对话失败: " + e.getMessage();
        }
    }

    // ── Background tools ─────────────────────────────────────────────

    /** submit_to_agent: queue a background task; returns the task id. */
    public String submit(String agentId, String task) {
        SubagentTaskRegistry.Task entry = registry.register(agentId);
        executor.submit(() -> {
            try {
                Path ws = workspaceFor(agentId);
                WorkspaceContext.set(ws.toString());
                HarnessAgent agent = buildAgent(agentId, ws, agentId);
                if (agent == null) {
                    registry.fail(entry.taskId, "Agent '" + agentId + "' 未配置有效的活动模型");
                    return;
                }
                String result = runAgent(agent, task, "task-" + entry.taskId);
                registry.complete(entry.taskId, result);
            } catch (Exception e) {
                log.warn("[subagent] background task {} failed", entry.taskId, e);
                registry.fail(entry.taskId, e.getMessage());
            } finally {
                WorkspaceContext.clear();
            }
        });
        return entry.taskId;
    }

    /** check_agent_task: query a background task. */
    public Map<String, Object> check(String taskId) {
        SubagentTaskRegistry.Task t = registry.get(taskId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == null) {
            m.put("status", "not_found");
            m.put("detail", "task_id 不存在或已过期: " + taskId);
            return m;
        }
        m.put("task_id", t.taskId);
        m.put("agent_id", t.agentId);
        m.put("status", t.status);
        m.put("result", t.result);
        m.put("error", t.error);
        return m;
    }

    // ── Internals ────────────────────────────────────────────────────

    private Path workspaceFor(String agentId) {
        try {
            return AgentStore.workspaceDirForAgent(agentId);
        } catch (Exception e) {
            return com.agent.coding.skill.SkillStore.WORKING_DIR;
        }
    }

    private HarnessAgent buildAgent(String name, Path workspace, String agentId) {
        OpenAIChatModel model = resolveModel(agentId);
        if (model == null) {
            return null;
        }
        return HarnessAgent.builder()
                .name(name)
                .sysPrompt(SYS_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(workspace)
                .hook(toolGuardHook)
                .build();
    }

    private OpenAIChatModel resolveModel(String agentId) {
        ModelSlot slot = modelRouting.resolveEffectiveModel(agentId);
        if (slot != null && slot.hasBoth()) {
            return modelRouting.buildOpenAIChatModel(slot.providerId(), slot.modelId());
        }
        return null;
    }

    /** Run one agent turn and collect the final text. */
    private String runAgent(HarnessAgent agent, String prompt, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("subagent")
                .build();
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        agent.streamEvents(new UserMessage(prompt), ctx)
                .doOnNext(event -> {
                    String type = event.getClass().getSimpleName();
                    try {
                        if ("TextBlockDeltaEvent".equals(type)) {
                            text.append(event.getClass().getMethod("getDelta").invoke(event));
                        } else if ("ThinkingBlockDeltaEvent".equals(type)) {
                            thinking.append(event.getClass().getMethod("getDelta").invoke(event));
                        }
                    } catch (Exception ignored) {
                    }
                })
                .blockLast(RUN_TIMEOUT);
        String result = text.toString().trim();
        if (result.isBlank() && !thinking.toString().isBlank()) {
            result = "[仅思考] " + thinking.toString().trim();
        }
        if (result.isBlank()) {
            return "(子任务无文本输出)";
        }
        return result.length() > MAX_RESULT_CHARS
                ? result.substring(0, MAX_RESULT_CHARS) + "\n...[结果过长已截断]" : result;
    }
}
