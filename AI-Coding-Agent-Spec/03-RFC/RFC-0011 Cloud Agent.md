## 元信息

| 项 | 值 |
|---|---|
| RFC 编号 | RFC-0011 |
| 标题 | Cloud Agent |
| 状态 | 🚧 大纲占位（Outline Only） |
| 关联 PRD | [P2 明确排除](../01-Product/PRD.md#3-产品范围本阶段-p0p1p2) |
| 关联架构 | [Overall Architecture §5.2](../02-Architecture/Overall%20Architecture.md#52-cloud-agent-模式p2架构预留) |
| 依赖 RFC | [RFC-0001 Agent Runtime](RFC-0001%20Agent%20Runtime.md)、[RFC-0006 Workspace](RFC-0006%20Workspace.md)、[RFC-0014 Sandbox](RFC-0014%20Sandbox.md) |

## 1. 背景与目标（待细化）

**本 RFC 仅做架构预留讨论，不构成 v1.0 交付范围。** Cloud Agent 处理长耗时/离线任务（类似 OpenHands/Codex 的云端异步模式），[PRD.md](../01-Product/PRD.md) 明确将"云端全自动 issue-to-PR 工作流"列为 P2 排除项。本 RFC 的价值在于确保 v1.0 的 Agent Runtime/Workspace 接口设计不会锁死未来向云端扩展的路径。

## 2. 本RFC需要回答的核心设计问题

1. 云端 Agent 与本地 Agent 是否复用同一套 Agent Runtime 核心逻辑，仅 Workspace/Sandbox 实现替换为云端版本？
2. 云端任务的触发方式是什么（webhook、定时任务、用户主动提交长任务）？
3. 云端执行结果如何同步回本地（用户下次打开本地工具时能看到云端任务的进展/结果）？
4. 云端场景下的成本模型如何设计（计算资源 + 模型调用的双重成本）？
5. 云端 Sandbox 的多租户隔离要求比本地单机场景高得多，需要什么级别的隔离强度？
6. 是否需要支持云端与本地协同的场景（本地开始一个任务，因为耗时长转移到云端继续执行）？

## 3. 建议章节结构

- 背景：为什么 v1.0 不做，未来做的触发条件
- 核心概念（Cloud Session/Async Task）
- 与本地 Agent Runtime 的接口复用设计
- 触发机制
- 结果同步机制
- 多租户隔离要求
- 成本模型
- 明确的非目标（v1.0）
- 开放问题

## 4. 已知的关键设计张力

- **架构预留 vs 过度设计**：为不存在的需求预留过多接口抽象可能导致 v1.0 架构复杂度不必要地增加，需要克制，仅做"不锁死路径"级别的预留，不做完整实现
- **本地优先哲学 vs 云端能力的价值**：[Product Philosophy](../00-Vision/Product%20Philosophy.md) 强调本地优先，Cloud Agent 若设计不当容易演变成"默认云端"，需要在架构上明确其"可选增强"定位

## 5. 前置依赖

- v1.0 本地 Agent Runtime、Workspace 需先稳定运行并验证，再评估云端扩展的实际必要性和优先级
- 需要 [Roadmap.md](../01-Product/Roadmap.md) 明确 M3 之后是否真的规划此能力，避免无依据的架构投入
