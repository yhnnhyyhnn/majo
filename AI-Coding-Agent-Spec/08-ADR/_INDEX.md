# 08-ADR — 架构决策记录

> **状态：🚧 6 篇 ADR 已完成** — 含推翻 ADR-0001 的技术栈转向决策。

## 本目录的定位

08-ADR（Architecture Decision Records）记录本规范体系中所有需要正式决策留痕的架构选择。呼应 [Product Philosophy](../00-Vision/Product%20Philosophy.md)"任何试图打破核心原则的功能提案，需要在 ADR 中明确记录理由"的要求，ADR 是保证决策可追溯、避免"口头决定后来无人记得为什么"的机制。

## ADR 模板

每个 ADR 使用以下标准格式，文件命名为 `ADR-XXXX-简短标题.md`：

```markdown
# ADR-XXXX：[决策标题]

## Status
[Proposed | Accepted | Deprecated | Superseded by ADR-YYYY]

## Context
（描述触发这个决策的背景、相关的设计张力，链接到相关 RFC/架构文档）

## Decision
（明确说明最终决定了什么，以及为什么选择这个方案而非其他候选方案）

## Consequences
（这个决策带来的正面影响、负面权衡、以及对其他模块/未来扩展的影响）
```

## ADR 索引

| ADR | 决策 | 状态 | 影响范围 |
|---|---|---|---|
| [ADR-0001](ADR-0001-language-selection.md) | Agent Core 实现语言选型：TypeScript/Node.js | Accepted | 工程规范、VS Code 插件、AI SDK 生态 |
| [ADR-0002](ADR-0002-sandbox-isolation.md) | Sandbox 隔离技术路线：进程级隔离（v1.0），容器级作为 P1+ 预留 | Accepted | RFC-0014 Sandbox、跨平台部署 |
| [ADR-0003](ADR-0003-embedding-strategy.md) | Context Engine Embedding 策略：本地轻量模型默认，云端 API 可选 | Accepted | RFC-0002 Context Engine、代码隐私 |
| [ADR-0004](ADR-0004-cli-vs-ide-priority.md) | CLI 与 IDE 插件开发优先级：CLI-first，IDE 插件 M2 启动 | Accepted | Roadmap、04-UX |
| [ADR-0005](ADR-0005-telemetry-protocol.md) | Telemetry 标准协议选型：自定义轻量方案 + OpenTelemetry 兼容 Schema | Accepted | RFC-0016 Telemetry、可观测性 |
| [ADR-0006](ADR-0006-java-agentscope.md) | 推翻 ADR-0001：Java + AgentScope Java 2.0 替代 TypeScript/Node.js | Accepted（Supersedes ADR-0001） | 全局技术栈、Technical Architecture、Deployment |

## 未来可能的 ADR 候选

以下是从已完成的 RFC "开放问题"章节中提取的待决策事项，可能在后续产生新的 ADR：

- Prompt Engine 模板版本管理机制（[RFC-0013](../03-RFC/RFC-0013%20Prompt%20Engine.md) 开放问题）
- 对话历史摘要压缩策略（[RFC-0013](../03-RFC/RFC-0013%20Prompt%20Engine.md) 开放问题）
- 本地自托管模型（Ollama/vLLM）接入是否复用 Model Router 的 ProviderAdapter 接口（[RFC-0012 §11](../03-RFC/RFC-0012%20Model%20Router.md#11-开放问题)）
- 网络策略的"确认疲劳"优化方案（[RFC-0014 §10](../03-RFC/RFC-0014%20Sandbox.md#10-开放问题)）
- `execute_command` 动态风险评定的危险模式列表维护策略（[RFC-0015 §11](../03-RFC/RFC-0015%20Permission.md#11-开放问题)）

## 正确的工作流

RFC 提出问题 → 团队讨论 → 产出 ADR → 决策结果回填到对应 RFC 正文。ADR 应该在决策发生的那一刻创建，而不是事后补文档——这是保证决策上下文不被遗忘的核心价值。
