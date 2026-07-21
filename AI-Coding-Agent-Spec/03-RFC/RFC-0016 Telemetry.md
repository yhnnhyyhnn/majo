## 元信息

| 项 | 值 |
|---|---|
| RFC 编号 | RFC-0016 |
| 标题 | Telemetry |
| 状态 | 🚧 大纲占位（Outline Only） |
| 关联 PRD | [非功能性需求-可观测性](../01-Product/PRD.md#5-非功能性需求) |
| 关联架构 | [Overall Architecture](../02-Architecture/Overall%20Architecture.md#3-模块职责总览) |
| 依赖 RFC | [RFC-0001 Agent Runtime](RFC-0001%20Agent%20Runtime.md)、[RFC-0012 Model Router](RFC-0012%20Model%20Router.md) |

## 1. 背景与目标（待细化）

Telemetry 提供结构化日志与可观测性，直接支撑 [Design Principles](../00-Vision/Design%20Principles.md) 原则 3"每个动作可追溯"，也是 [KPI.md](../01-Product/KPI.md) 中产品质量指标的数据采集来源。

## 2. 本RFC需要回答的核心设计问题

1. 本地优先的哲学下（[Product Philosophy](../00-Vision/Product%20Philosophy.md)），Telemetry 数据默认仅本地留存，还是需要用户显式同意后上传聚合数据用于产品改进？
2. 结构化日志的 Schema 如何设计，才能同时满足"用户调试自己遇到的问题"和"团队分析产品质量指标"两种不同粒度的需求？
3. 每个工具调用的推理上下文（呼应 [Design Principles](../00-Vision/Design%20Principles.md) 原则 3"关联的推理片段"）如何与调用记录关联存储，而不是孤立事件？
4. 敏感信息（代码内容、API Key）在日志中如何脱敏，避免 Telemetry 本身成为安全风险面？
5. 实时监控（Agent 当前状态可视化，呼应可解释性支柱）与离线分析（KPI 计算）是否需要不同的数据管道？
6. 模型调用的成本追踪（token 用量、调用次数）如何与 [RFC-0012 Model Router](RFC-0012%20Model%20Router.md) 集成埋点？

## 3. 建议章节结构

- 核心概念（Event/Trace/Span，是否采用 OpenTelemetry 标准）
- 日志 Schema 设计
- 本地存储 vs 可选上报的数据分级策略
- 敏感信息脱敏规则
- 实时状态展示与离线分析的数据管道设计
- 成本追踪埋点规范
- 隐私合规考量
- 验收标准
- 开放问题

## 4. 已知的关键设计张力

- **数据完整性 vs 隐私保护**：越详细的日志越有利于调试和产品优化，但代码相关数据的敏感性要求默认最小化收集，需要在"本地全量留存"和"云端聚合分析需脱敏"之间设计清晰的数据分级边界
- **实时性 vs 存储开销**：全量 Trajectory 日志（含每个 Prompt/Response）价值高但存储量大，需要设计合理的采样/归档策略（呼应 [Database.md](../02-Architecture/Database.md) 数据生命周期章节）

## 5. 前置依赖

- 需要先确定是否采用 OpenTelemetry 等行业标准协议，还是自定义轻量方案，这决定后续所有埋点设计的基础形态
- 与 [Database.md](../02-Architecture/Database.md) 协同确定 Trajectory 全量日志的存储方案
