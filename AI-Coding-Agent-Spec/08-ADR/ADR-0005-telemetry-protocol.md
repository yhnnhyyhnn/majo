# ADR-0005：Telemetry 标准协议选型

## Status

Accepted

## Context

[RFC-0016 Telemetry](../03-RFC/RFC-0016%20Telemetry.md)（大纲）需要在"采用 OpenTelemetry 标准协议"与"自定义轻量方案"之间做出选择。[Design Principles](../00-Vision/Design%20Principles.md) 原则 3 要求"每个动作可追溯"，原则 10 要求"可验证优先于感觉好用"——这两条都需要 Telemetry 提供结构化、可靠的数据采集。

影响本决策的关键因素：

1. **v1.0 部署形态**：本地单进程或极少组件（Agent Core 一个进程），不是分布式微服务架构。OpenTelemetry 的完整价值（分布式 Tracing、跨服务 Span 传播）在单进程场景下几乎用不到。
2. **安装门槛**：OpenTelemetry 依赖一个 Collector 进程（`otelcol`）做数据收集和导出——这增加了用户的安装依赖，与 [Product Philosophy](../00-Vision/Product%20Philosophy.md)"本地优先、零强制外部依赖"冲突。
3. **数据隐私**：[Product Philosophy](../00-Vision/Product%20Philosophy.md) 要求 Telemetry 数据默认仅本地留存。OpenTelemetry 的主要设计目标是将数据导出到远端分析平台，其本地持久化能力不是核心功能。
4. **与 KPI 数据需求的对齐**：[KPI.md](../01-Product/KPI.md) 大纲定义的指标（任务完成率、diff 接受率等）是产品级聚合指标，不是分布式 Tracing 的 Span 级指标——自定义轻量方案更匹配数据消费端的实际形态。
5. **未来扩展性**：P2 阶段如果有云端多副本部署，此时 OpenTelemetry 的分布式 Tracing 价值会显现——但不应为了 P2 的假设需求而增加 v1.0 单机场景的复杂度。

## Decision

**v1.0 采用自定义轻量结构化日志方案（JSON Lines + SQLite 本地存储）。OpenTelemetry 兼容性仅体现在日志 Schema 设计上——事件字段命名遵循 OpenTelemetry Semantic Conventions 标准，但不引入 OpenTelemetry SDK 或 Collector 依赖。**

理由：

1. v1.0 是单进程架构，不需要 OpenTelemetry 的分布式 Tracing——引入其整个依赖链（SDK + Collector + Exporter）是对单进程场景的过度工程化
2. 结构化日志（JSON Lines）写本地 SQLite 的方案足以满足 [Design Principles](../00-Vision/Design%20Principles.md) 原则 3"每个动作可追溯"的要求
3. 日志 Schema 遵循 OpenTelemetry Semantic Conventions（如 `resource.attributes`、`span.name`、`trace.id` 的命名和语义），确保如果未来切换到 OpenTelemetry，已有日志数据可以低成本迁移，不会因为字段命名不一致导致历史数据需要重新解析

**备选策略（未采用的理由）**：
- **完全自定义，不考虑 OpenTelemetry 兼容**：短期最快但长期会增加迁移成本——遵循 Semantic Conventions 的边际成本极低，但未来省下的迁移工作量很可观
- **v1.0 即引入完整 OpenTelemetry 栈**：单进程场景下的过度工程化，且强制引入外部依赖（Collector 进程）违背本地优先哲学

## Consequences

### 正面影响

- 零额外依赖——日志直接写本地 SQLite，不需要 `otelcol` 等外部进程
- 开发复杂度低——简单的 Logger 接口 vs OpenTelemetry 的 Tracer/Meter/Exporter 完整配置链
- 用户隐私可控——所有日志默认仅本地留存，需要上报时由用户主动触发（通过 [Telemetry §2 大纲](../03-RFC/RFC-0016%20Telemetry.md) 的数据分级策略）

### 负面权衡

- 如果 P2 阶段确实需要分布式 Tracing，需要从自定义方案迁移到 OpenTelemetry——但 Schema 兼容设计已将迁移成本降到最低
- 不使用 OpenTelemetry Collector 意味着没有现成的数据导出到 Grafana/Datadog 等分析平台的适配器——需要为每个导出目标写自定义 Exporter（但 v1.0 阶段导出目标是"无"，不需要）

### 对已有 RFC 的影响

- [RFC-0016 Telemetry](../03-RFC/RFC-0016%20Telemetry.md) 完整版定稿时应以本 ADR 为基础设计具体的日志 Schema——遵循 OpenTelemetry Semantic Conventions 的字段命名
- [Database.md](../02-Architecture/Database.md) 需要确认 SQLite 方案是否也适用于 Telemetry 日志存储，还是需要独立的 SQLite 实例
- [Model Router](../03-RFC/RFC-0012%20Model%20Router.md) 的 §8 成本追踪埋点已定义 `TokenUsage` 接口——这个接口的 emite（event emitter）机制可以在自定义方案中直接实现，不需要适配 OpenTelemetry
