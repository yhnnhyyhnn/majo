# Sequence — 关键时序图

> **状态：✅ 完整** — 基于 10 篇 P0 RFC 的完整接口定义，绘制 8 个覆盖主流程、异常路径、子系统交互的时序图。与 [Overall Architecture §4](Overall%20Architecture.md#4-核心交互流程简化时序) 的简化时序互补——本文档提供完整交互细节。

## 场景 1：任务执行主流程（完整版）

```mermaid
sequenceDiagram
    actor User
    participant CLI
    participant AR as Agent Runtime
    participant CE as Context Engine
    participant PE as Prompt Engine
    participant MR as Model Router
    participant LLM
    participant TR as Tool Runtime
    participant PERM as Permission
    participant SB as Sandbox
    participant WS as Workspace
    participant TE as Task Engine

    User->>CLI: "给用户模块加邮箱验证"
    CLI->>AR: submitTask(sessionId, userPrompt)
    AR->>CE: 请求相关代码上下文
    CE->>CE: 三路检索（符号+语义+结构）
    CE-->>AR: Context Card 集合

    AR->>PE: 组装 Prompt（含 System Prompt + Context Cards + 工具描述）
    PE->>MR: UnifiedRequest
    MR->>LLM: API 请求（经 Provider Adapter）
    LLM-->>MR: 流式 Token Stream + 工具调用意图
    MR-->>PE: UnifiedStreamChunk
    PE-->>AR: Plan + ToolCalls

    AR->>PERM: 判定操作风险
    PERM-->>AR: needs_confirmation（L2 下 write 需确认）

    AR->>CLI: 展示 Plan + 等待用户确认
    CLI->>User: "计划：修改 src/auth/login.ts (+15 -3)"
    User-->>CLI: 确认

    loop 每个 Turn
        AR->>TR: 执行工具调用
        TR->>PERM: 再次判定（参数可能变化）
        PERM-->>TR: allowed
        TR->>SB: execute_command（如需要）
        SB->>WS: 经文件系统视图写入
        TR-->>AR: Observation
        AR->>TE: saveSnapshot
        TE-->>AR: snapshotId
        AR->>CE: 请求补充上下文（基于观察结果）
        CE-->>AR: 更新 Context Card
    end

    AR->>CLI: 任务完成 + Diff 摘要
    CLI->>User: 展示 Review 界面
```

## 场景 2：Session 中断与恢复

```mermaid
sequenceDiagram
    actor User
    participant CLI
    participant TE as Task Engine
    participant AR as Agent Runtime
    participant WS as Workspace

    Note over User,WS: === 中断前 ===
    User->>CLI: 启动 Agent，Task 执行中
    Note over AR: Turn 3 执行中

    User->>CLI: Ctrl+C 或进程崩溃
    Note over TE: Task Engine 在崩溃前<br/>已保存 Turn 2 的 Snapshot

    Note over User,WS: === 中断期间 ===
    User->>WS: 用户手动编辑了 src/auth/login.ts

    Note over User,WS: === 恢复 ===
    User->>CLI: agent resume --session <id>
    CLI->>TE: resumeSession(sessionId)
    TE->>TE: 加载最近 Snapshot（Turn 2）

    TE->>WS: 一致性校验：对比 Snapshot.modifiedFiles
    WS-->>TE: Warning: src/auth/login.ts 的 mtime/sha256 不一致

    TE-->>CLI: ResumeWarning: EXTERNAL_MODIFICATION
    CLI->>User: "以下文件在 Session 外被修改：src/auth/login.ts<br/>[回退 Agent 改动] [忽视继续] [放弃 Session]"
    User-->>CLI: 回退 Agent 改动

    TE->>WS: Git 回退到 SafeBase commit
    CLI->>AR: 从 Turn 2 之后重新开始
    AR->>AR: 基于 Plan + Workspace 当前状态继续执行
```

## 场景 3：工具调用失败重试

```mermaid
sequenceDiagram
    participant AR as Agent Runtime
    participant TR as Tool Runtime
    participant PERM as Permission
    participant SB as Sandbox
    participant PE as Prompt Engine
    participant LLM

    AR->>TR: execute_command("npm test")
    TR->>PERM: 风险判定
    PERM-->>TR: allowed
    TR->>SB: 在沙箱中执行命令
    SB-->>TR: 失败（exit code 1, timeout）

    TR-->>AR: Observation(errorCode=TIMEOUT, content="测试套件执行超时")

    Note over AR: PartialFailure 状态
    AR->>PE: 请求 Reflect：分析失败原因
    PE->>LLM: "测试超时，可能是某个测试用例挂起。<br/>尝试加 --timeout=120 重试"
    LLM-->>AR: 建议重试 + 修改超时参数

    AR->>TR: execute_command("npm test -- --timeout=120")
    TR->>SB: 重试执行
    SB-->>TR: 成功

    TR-->>AR: Observation(status=success)
    Note over AR: 重试次数清零，继续下一个 Turn
```

## 场景 4：多文件编辑与 Review 确认

```mermaid
sequenceDiagram
    actor User
    participant CLI
    participant AR as Agent Runtime
    participant WS as Workspace
    participant RV as Review
    participant Git

    AR->>WS: edit_file("src/auth/login.ts", oldString, newString)
    WS-->>AR: FileSnapshot updated
    AR->>WS: edit_file("src/auth/validate.ts", oldString, newString)
    AR->>WS: write_file("src/auth/verify.ts", content)

    AR->>WS: takeSnapshot(["src/auth/*.ts"])
    WS-->>AR: 3 个文件的 FileSnapshots
    AR->>RV: 触发 Review（DiffSet 生成）

    RV->>Git: git diff（获取统一格式 Diff）
    Git-->>RV: unified diff（含 3 个文件，12 个 Hunks）

    RV->>CLI: 展示摘要："3 文件，12 Hunks"
    CLI->>User: "src/auth/login.ts (+15 -3) | src/auth/validate.ts (+42 -0) | src/auth/verify.ts (new)"

    User-->>CLI: 展开 login.ts
    CLI->>User: 展示 login.ts 的 4 个 Hunks
    User-->>CLI: 接受 Hunk #1, #2, #4；拒绝 Hunk #3（理由：使用了 deprecated API）

    CLI->>RV: submitReview({decisions: [accept, accept, reject("deprecated API"), accept]})
    RV-->>AR: ReviewResult: 3 accepted, 1 rejected

    Note over AR: Reflect 阶段<br/>"用户拒绝了 Hunk #3（理由：不应使用 deprecated API）"
    AR->>AR: 下一个 Turn 中使用新 API 替代
```

## 场景 5：渐进自主性降级

```mermaid
sequenceDiagram
    participant AR as Agent Runtime
    participant PERM as Permission
    actor User

    Note over AR: Session 配置为 L3（自主模式）
    Note over AR: write 操作自动执行

    AR->>PERM: execute_command("rm -rf src/legacy/")
    PERM->>PERM: 动态风险分析
    Note over PERM: 模式匹配: "rm -rf"<br/>上下文分析: 非 node_modules<br/>→ riskTier: high

    PERM->>PERM: HardConfirmList 检查
    Note over PERM: 命中: rm -rf 删除非构建产物路径<br/>→ needs_confirmation（强确认）

    PERM-->>AR: needs_confirmation（强确认，不可被 L3 绕过）
    AR->>User: "操作需要强确认：rm -rf src/legacy/<br/>风险：不可逆数据删除<br/>此操作在任何自主性级别下都需要确认"

    User-->>AR: 确认
    Note over PERM: PermissionDecision(decision=allowed, wasEscalated=false)
    AR->>AR: 执行删除操作
```

## 场景 6：Git 分支隔离与回退

```mermaid
sequenceDiagram
    actor User
    participant AR as Agent Runtime
    participant Git
    participant WS as Workspace
    participant TE as Task Engine

    User->>AR: "给用户模块加邮箱验证"
    AR->>Git: 检查当前 Git 状态
    Git-->>AR: branch=feature/login, Working Directory clean

    AR->>Git: git checkout -b agent/task-20260721-abc12
    Git-->>AR: 新分支创建成功

    Note over AR: SafeBase: { branch: "feature/login",<br/>commit: "abc1234" }

    loop Agent 执行 Task
        AR->>WS: 修改文件
    end

    User->>AR: "我不喜欢这些改动，全部回退"
    AR->>Git: 检查是否有 push
    Git-->>AR: 未 push，安全回退

    AR->>Git: git checkout feature/login
    AR->>Git: git branch -D agent/task-20260721-abc12
    Git-->>AR: WorkBranch 已删除

    AR->>WS: 验证 Workspace 文件状态
    WS-->>AR: 文件状态与 SafeBase commit 一致
    AR->>User: "回退完成，代码库已恢复到 Task 开始前的状态"
```

## 场景 7：上下文预算裁剪

```mermaid
sequenceDiagram
    participant AR as Agent Runtime
    participant CE as Context Engine
    participant MR as Model Router

    AR->>CE: 请求上下文（当前 Task + 代码库）
    CE->>CE: 三路检索 → Reranking → 候选 25 个 Context Cards

    CE->>MR: 查询当前模型的 Context Window 大小
    MR-->>CE: 200K tokens

    CE->>CE: 计算 Budget
    Note over CE: System Prompt: 5K<br/>工具描述: 3K<br/>历史摘要: 8K<br/>可用 Context Budget: 184K

    CE->>CE: 按优先级填充
    Note over CE: P1: 用户@的文件 (3 Cards, 15K)<br/>P2: Top-N 高分 Cards (18/22 个, 160K)<br/>P3: 剩余 4 个 Card 因 Budget 不足被裁剪

    CE-->>AR: 21 个 Context Card + 裁剪警告
    Note over AR: "上下文已被裁剪，<br/>4 个相关文件未注入"

    AR->>AR: 将裁剪信息传递给 Agent<br/>Agent 可以在需要时<br/>主动请求这些文件
```

## 场景 8：索引增量更新

```mermaid
sequenceDiagram
    actor User
    participant Editor as 外部编辑器
    participant WS as Workspace
    participant EB as EventBus
    participant CE as Context Engine

    Editor->>Editor: 用户修改 src/auth/login.ts 并保存

    Editor->>WS: 文件系统写入
    WS->>WS: FSEvents 检测到文件变更
    Note over WS: debounce 500ms<br/>合并同一文件的多次事件

    WS->>EB: publish(file:changed)
    EB->>CE: file:changed 事件

    CE->>CE: 解析变更文件的新 AST
    CE->>CE: 重新分块（按函数边界）
    CE->>CE: 生成新 Embedding

    CE->>CE: 更新索引
    Note over CE: 删除旧的 login.ts 相关向量行<br/>插入新的向量行<br/>仅变更文件，非全量重建

    CE->>CE: 更新符号索引（函数名、位置）
    Note over CE: 整个增量更新 < 5s
```

## 时序图与现有 RFC 的交叉校验

| 场景 | 验证的 RFC 设计 | 关键接口一致性 |
|---|---|---|
| 场景 1 | RFC-0001、RFC-0002、RFC-0003、RFC-0015 | ✅ Agent Runtime → Context Engine → Prompt Engine → Model Router → Tool Runtime 链路闭环 |
| 场景 2 | RFC-0004 §4.2、RFC-0006 | ✅ Task Engine 恢复流程 + Workspace 一致性校验（mtime/sha256 三元组） |
| 场景 3 | RFC-0001 §3 (PartialFailure)、RFC-0003 §4 | ✅ PartialFailure → Reflecting → 调整参数 → 重试 |
| 场景 4 | RFC-0007 §3-6、RFC-0008 §5 | ✅ Workspace Snapshot Diff + Git unified diff → Review 展示 → 拒绝反馈回 AR |
| 场景 5 | RFC-0015 §4.1、§5 | ✅ 动态风险分析 → HardConfirmList → L3 无法绕过强确认 |
| 场景 6 | RFC-0008 §3、§6 | ✅ 智能分支策略 + SafeBase + 全量回退 |
| 场景 7 | RFC-0002 §6、§6.1 | ✅ Budget 瀑布分配 + 裁剪警告 |
| 场景 8 | RFC-0002 §4、RFC-0006 §4、Event Bus | ✅ 文件变更 → EventBus → Context Engine 增量重建 |
