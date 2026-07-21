# API Contract — 接口契约

> **状态：✅ 完整** — 基于 [Domain Model](../02-Architecture/Domain%20Model.md) 实体和 [API](../02-Architecture/API.md) 设计，给出可落地的 Java DTO、Spring Shell 命令和 AgentScope Event 映射。

## 1. Java DTO 类定义

所有 DTO 在 `com.agent.coding.dto` 包下：

```java
package com.agent.coding.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// =====================================================
// Session DTOs
// =====================================================
public record CreateSessionRequest(
    String workspacePath,
    Config config
) {
    public record Config(
        String autonomyLevel,   // L1|L2|L3|L4, default L2
        String modelId,         // AgentScope 格式: "qwen:qwen-plus"
        boolean overrideConfig  // 是否覆盖 config.yaml
    ) {}
}

public record CreateSessionResponse(
    String sessionId,
    String status,     // Created|Active
    Instant createdAt
) {}

public record ResumeSessionRequest(
    String sessionId,
    boolean autoContinue   // 跳过恢复确认
) {}

public record ResumeSessionResponse(
    String sessionId,
    String lastTaskId,
    int lastTurnIdx,
    String status,
    List<ResumeWarning> warnings
) {}

public record ResumeWarning(
    String type,       // EXTERNAL_MODIFICATION | GIT_STATE_CHANGED
    String path,
    String detail
) {}

// =====================================================
// Task DTOs
// =====================================================
public record SubmitTaskRequest(
    String sessionId,
    String userPrompt,
    TaskContext context
) {}

public record TaskContext(
    List<String> attachedFiles,    // 用户 @ 的文件路径
    String attachedCode,           // 用户粘贴的代码片段
    String previousTaskId          // 继承上一个 Task 上下文
) {}

public record SubmitTaskResponse(
    String taskId,
    String status,         // TaskCreated
    String estimatedPlan   // Agent 初步规划摘要
) {}

public record CancelTaskRequest(
    String taskId,
    String mode            // graceful | force
) {}

public record CancelTaskResponse(
    String taskId,
    String status,         // Cancelling | Cancelled
    int completedTurnIdx,
    List<String> pendingToolCalls
) {}

// =====================================================
// Review DTOs
// =====================================================
public record SubmitReviewRequest(
    String taskId,
    List<ReviewDecisionInput> decisions,
    String batchMode       // all_accept | all_reject (可选)
) {}

public record ReviewDecisionInput(
    String hunkId,
    String decision,       // accept | reject | edit
    String reason,         // 拒绝理由（注入下 Turn Prompt）
    String editedContent   // decision=edit 时有效
) {}

public record SubmitReviewResponse(
    String taskId,
    int acceptedHunks,
    int rejectedHunks,
    int editedHunks,
    String nextAction      // continue | complete
) {}

// =====================================================
// History DTOs
// =====================================================
public record ListTasksRequest(
    String workspacePath,
    String olderThan,      // ISO 8601, 默认 30 天前
    int limit,             // 默认 50
    int offset
) {}

public record TaskSummary(
    String taskId,
    String sessionId,
    String workspacePath,
    String userPrompt,
    String status,
    int turnCount,
    Instant createdAt,
    Instant completedAt
) {}

public record TrajectoryResponse(
    String taskId,
    List<TurnDetail> turns
) {}

public record TurnDetail(
    int idx,
    Map<String, Object> planState,
    List<ToolCallDetail> toolCalls,
    TokenUsage tokenUsage
) {}

public record ToolCallDetail(
    String callId,
    String toolName,
    Map<String, Object> parameters,
    ObservationSummary observationSummary,
    String observationFull    // 仅 full 模式
) {}

public record ObservationSummary(
    String status,
    String contentPreview    // 前 200 字符
) {}

public record TokenUsage(
    int inputTokens,
    int outputTokens,
    double estimatedCostUSD
) {}

// =====================================================
// Error Response
// =====================================================
public record ApiError(
    String code,           // WORKSPACE_IN_USE | SESSION_NOT_FOUND | ...
    String message,
    String taskId,
    boolean recoverable,
    Map<String, Object> details
) {}
```

## 2. Spring Shell CLI 命令

```java
@ShellComponent
public class AgentCommands {

    private final AgentService agentService;

    // === Session 命令 ===
    @ShellMethod(value = "创建新 Session", key = "agent start")
    public String start(
        @ShellOption({"-w", "--workspace"}) String workspace,
        @ShellOption(value = {"-a", "--autonomy"}, defaultValue = "L2") String autonomy,
        @ShellOption(value = {"-m", "--model"}) String model
    ) {
        // 调用 agentService.createSession(...)
    }

    @ShellMethod(value = "恢复 Session", key = "agent resume")
    public String resume(
        @ShellOption({"-s", "--session"}) String sessionId,
        @ShellOption(value = "--auto-continue", defaultValue = "false") boolean autoContinue
    ) {
        // 调用 agentService.resumeSession(...)
    }

    @ShellMethod(value = "关闭 Session", key = "agent close")
    public String close(@ShellOption({"-s", "--session"}) String sessionId) { }

    @ShellMethod(value = "列出所有 Session", key = "agent sessions")
    public String listSessions() { }

    // === Task 命令 ===
    @ShellMethod(value = "提交任务", key = "agent task")
    public String submitTask(
        @ShellOption({"-p", "--prompt"}) String prompt,
        @ShellOption(value = {"-f", "--files"}, arity = -1) List<String> files
    ) { }

    @ShellMethod(value = "取消当前任务", key = "agent cancel")
    public String cancelTask(
        @ShellOption(value = "--mode", defaultValue = "graceful") String mode
    ) { }

    // === Review 命令 ===
    @ShellMethod(value = "接受所有 Diff", key = "agent accept-all")
    public String acceptAll() { }

    @ShellMethod(value = "拒绝所有 Diff", key = "agent reject-all")
    public String rejectAll() { }

    @ShellMethod(value = "审查 Diff（交互模式）", key = "agent review")
    public String review() { }

    // === History 命令 ===
    @ShellMethod(value = "查看任务历史", key = "agent history")
    public String history(
        @ShellOption(value = "--days", defaultValue = "30") int days
    ) { }

    @ShellMethod(value = "查看任务详情", key = "agent history detail")
    public String historyDetail(@ShellOption({"-t", "--task"}) String taskId) { }

    // === Config 命令 ===
    @ShellMethod(value = "查看/修改配置", key = "agent config")
    public String config(
        @ShellOption(value = "--get") String key,
        @ShellOption(value = "--set") String value
    ) { }

    // === Clean 命令 ===
    @ShellMethod(value = "清理过期数据", key = "agent clean")
    public String clean(
        @ShellOption(value = "--older-than", defaultValue = "30d") String olderThan,
        @ShellOption(value = "--trajectories", defaultValue = "false") boolean trajectories,
        @ShellOption(value = "--all", defaultValue = "false") boolean all
    ) { }
}
```

## 3. AgentScope Event → TUI 渲染映射

AgentScope 的 `streamEvents()` 发射 28 种 `AgentEvent`。下表定义每种事件在 TUI 中如何渲染：

| AgentScope AgentEvent | TUI 渲染行为 | 更新组件 |
|---|---|---|
| `TextDeltaEvent` | 流式输出 LLM 文本内容 | `StreamRenderer` → 主输出区 |
| `ToolCallStartedEvent` | 展示"🔧 调用工具：{toolName}" | `StatusLine` |
| `ToolCallProgressEvent` | 更新工具调用耗时 | `StatusLine` |
| `ToolCallCompletedEvent` | 展示工具结果摘要（截断） | `StreamRenderer` |
| `ToolCallFailedEvent` | 红色错误提示 + 错误码 | `StreamRenderer` |
| `ThinkingStartedEvent` | "🧠 思考中..." | `StatusLine` |
| `ThinkingDeltaEvent` | 流式展示推理过程（可收起） | `StreamRenderer`（折叠区） |
| `ThinkingCompletedEvent` | 收起思考区 | `StreamRenderer` |
| `UserConfirmationRequiredEvent` | 展示确认弹窗：操作摘要 + 风险级别 + 原因 | `ReviewPanel` |
| `UserConfirmationResultEvent` | 展示确认结果（✓/✗） | `StreamRenderer` |
| `PlanGeneratedEvent` | 展示 Plan 步骤列表 | `TaskView` |
| `PlanUpdatedEvent` | 更新 Plan（标记已完成步骤） | `TaskView` |
| `TurnStartedEvent` | 更新 Turn 计数器 | `StatusLine` |
| `TurnCompletedEvent` | "✓ Turn {n} 完成 (耗时 {t}ms)" | `StatusLine` |
| `TaskCompletedEvent` | "🎉 任务完成" + 最终摘要 | `StreamRenderer` |
| `TaskFailedEvent` | 红色错误摘要 + 恢复建议 | `StreamRenderer` |
| `CompactionEvent` | "📦 上下文已压缩" | `StatusLine` |
| `TokenUsageEvent` | 更新 Token 计数器 + 成本 | `StatusLine` |
| `ModelCallStartedEvent` | "📡 调用 {modelName}..." | `StatusLine` |
| `ModelCallCompletedEvent` | 更新模型调用耗时 | `StatusLine` |
| `ModelFallbackEvent` | "⚠ 主模型不可用，降级到 {fallbackModel}" | `StatusLine` |
| `InterruptedEvent` | "⏸ 已暂停" | `StatusLine` |
| `ErrorEvent` | 红色错误弹窗 | `StreamRenderer` |
| `WarningEvent` | 黄色警告提示 | `StreamRenderer` |
| `SubAgentSpawnedEvent` | "🤖 子Agent已启动：{name}"（M3） | `StatusLine` |
| `SubAgentResultEvent` | 子Agent结果摘要（M3） | `StreamRenderer` |
| `PermissionDeniedEvent` | "🚫 操作被拒绝：{reason}" | `StreamRenderer` |
| `HookEvent` | 根据 Hook 类型选择性展示 | 调试模式可见 |

**渲染规则**：
- `StreamRenderer` 负责流式文本输出和事件摘要——是最大最复杂的 UI 组件
- `StatusLine` 是底部固定状态栏——展示 Turn 序号、Token 用量、当前状态
- `TaskView` 负责 Plan 步骤的展开/收起和进度条
- `ReviewPanel` 是弹出式交互面板——Diff 展示 + 逐 Hunk 审查
- 所有事件是单向推送——TUI 订阅 EventStream，不轮询 Agent 状态
