# ADR-0006：推翻 ADR-0001——Java + AgentScope 替代 TypeScript/Node.js

## Status

Accepted（Supersedes ADR-0001）

## Context

ADR-0001 选择 TypeScript/Node.js 的决定基于三个核心假设：
1. VS Code 插件需要 TypeScript——同语言可共享类型定义
2. AI SDK 生态以 TypeScript/JS 为一等公民
3. 团队技能未明确，TypeScript 是安全选择

经过用户访谈（[Persona.md](../01-Product/Persona.md)）、产品范围确认和框架调研，这三个假设都发生了变化：

1. **VS Code 插件不再是 v1.0 需求**：产品范围明确为 TUI + 桌面版，不做 IDE 插件。TypeScript 在 VS Code 生态中的天然优势不再有效。
2. **不使用官方 SDK**：不使用 OpenAI/Anthropic 官方 SDK——改用 AgentScope Java 2.0 的统一 ModelRegistry（`agentscope-extensions-model-*`），直接消除了 TypeScript SDK 生态的优势。
3. **团队技能倾向 Java**：明确团队更熟悉 Java 生态。
4. **框架红利**：AgentScope Java 2.0 原生覆盖了本产品 60-70% 的 P0 能力——Agent Runtime (ReActAgent+HarnessAgent)、Tool Runtime (@Tool)、Permission (PermissionEngine)、Model Router (ModelRegistry)、Sandbox、MCP、State Store、Telemetry (OpenTelemetry)。在 TypeScript 生态中没有同等覆盖度的现成框架——需要从零实现所有 P0 RFC。

## Decision

**选用 Java 21 LTS + AgentScope Java 2.0 + Spring Boot + GraalVM native-image。**

`io.agentscope:agentscope-harness:2.0.0` 作为核心依赖。

理由：

1. **框架覆盖度**：AgentScope 提供的 `HarnessAgent` 原生覆盖了 Agent Runtime、Workspace、Memory、Compaction、Sandbox、Sub-agent、Skills、Plan Mode。`PermissionEngine` 提供 allow/approve/deny 三层判定——与 RFC-0015 的 L1-L4 自主性设计高度兼容。`ModelRegistry` 支持 6 家 Provider。这些在 TypeScript 生态中都需要从零实现。

2. **Java 运行时特性**：虚拟线程（Java 21 Virtual Threads）在 I/O 密集型场景下性能优于 Node.js Event Loop；类型系统（Records/Sealed Classes/Pattern Matching）比 TypeScript 的运行时类型检查更可靠。

3. **开源生态对齐**：AgentScope GitHub 4K+ stars，Maven Central 发布，Maven 多模块结构成熟。内置 MCP/A2A 协议。

4. **本地优先哲学契合**：GraalVM native-image 编译对 CLI 工具的冷启动优化远优于 Node.js SEA（有 Quarkus 成熟生态支持）。AgentScope 的 `JsonFileAgentStateStore` 默认本地文件存储，可随时切换 `RedisAgentStateStore`。

5. **团队效率**：从"造 Runtime"变成"用 Runtime"——核心 Agent 循环、工具注册、Permission 判定、Session 管理、MCP 协议、Telemetry 全部由框架提供。团队只需实现 Context Engine 检索策略、Git 集成、CLI/TUI 界面和业务工具。

**备选策略（未采用）**：
- 坚持 ADR-0001 TypeScript：在 VS Code 插件已不在范围的前提下，TypeScript 仅剩"AI SDK 生态好"一个优势——但这个优势被 AgentScope 的 ModelRegistry 直接消除。无框架红利意味着所有 P0 RFC 都需要从零造——工程成本远高于 Java 方案。
- 混合方案（Java + TypeScript）：这已在上一轮分析中被否决——跨语言 Core 增加依赖管理、构建工具链、调试体验的全面恶化。

## Consequences

### 正面影响

- **工程效率质变**：AgentScope 原生覆盖的 P0 能力使我们只需实现 ~30% 的代码（Context Engine 检索定制、Git 集成、CLI/TUI、业务工具）
- **天然可扩展**：AgentScope 的 Maven 多模块结构 + Middleware 五阶段洋葱模型，使得新增能力（如新的 Model Provider、新的 Sandbox 后端、新的 Tool）通过插件式扩展，不修改核心
- **企业级就绪**：AgentScope 原生支持分布式（Redis/JDBC State Store）、OpenTelemetry、A2A 协议——为 P2 Cloud Agent 和 Enterprise 提供了直接路径
- **GraalVM native-image** 冷启动可达 200ms，比 Node.js SEA 更轻量

### 负面权衡

- Java 生态的 TUI/桌面框架不如 TypeScript 生态成熟——需要评估 Java 的 Terminal UI 库（如 Lanterna/Jexer/JLine）vs TypeScript 的 Ink/Chalk
- AgentScope 是较新的框架（v2.0 2026年7月 GA），社区生态和文档成熟度不如 TypeScript AI SDK 生态
- 团队需要适应 Project Reactor（响应式编程范式）——AgentScope 基于 Mono/Flux

### 对已有规范的影响

- **RFC-0012 Model Router**：可大幅简化——ModelRegistry 已经提供了 Provider Adapter + 重试/降级。我们只需配置，不需要实现 Provider Adapter 抽象层
- **RFC-0001 Agent Runtime**：`HarnessAgent` 替代 `runAgenticLoop` 自研实现。Sub-agent 编排由 AgentScope 的 `agent_spawn`/`agent_send` 提供
- **RFC-0003 Tool Runtime**：`@Tool` 注解替代自研 ToolRegistry。Tool 注册、调度、并发控制由框架负责
- **RFC-0004 Task Engine**：`AgentStateStore` + `(userId, sessionId)` 替代自研 Snapshot 和恢复逻辑
- **RFC-0006 Workspace**：`WorkspaceBase` 替代自研文件抽象
- **RFC-0014 Sandbox**：AgentScope 原生 Sandbox 替代自研——我们只需配置策略
- **RFC-0015 Permission**：`PermissionEngine` 提供三层判定基础——我们需要在其上实现 L1-L4 自主性级别和 HardConfirmList
- **RFC-0016 Telemetry**：OpenTelemetry 原生集成——ADR-0005 的"自定义轻量方案+OTEL兼容Schema"不再需要——直接用 AgentScope 的 Telemetry
- **Technical Architecture.md** 和 **Deployment.md** 需要重写为 Java 技术栈

**不需要修改的文档**：
- 00-Vision 全部、01-Product 全部（除了 Persona/Roadmap 中的"TypeScript"提法）
- RFC-0002 Context Engine（检索策略仍是我们自研）
- RFC-0007 Review / RFC-0008 Git（AgentScope 不提供 Diff Review 和 Git 工作流——仍需自研）
- 08-ADR-0002~0005（Sandbox/Embedding/CLI-first/Telemetry 策略方向不变，仅实现层从 TS 变 Java）
