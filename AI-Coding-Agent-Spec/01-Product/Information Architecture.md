# Information Architecture — 信息架构

> **状态：🚧 大纲占位** — 本文档尚未完整撰写，以下是该章节应回答的核心问题清单和结构大纲，供后续迭代填充。

## 本章节应回答的核心问题

1. CLI 模式下，命令结构是怎样的（单一交互式对话入口 vs 多子命令如 `agent plan` / `agent run` / `agent review`）？
2. Session、Task、Trajectory（见 [RFC-0001](../03-RFC/RFC-0001%20Agent%20Runtime.md)）在用户可见的信息层级中如何呈现，用户如何在多个 Session 间切换？
3. IDE 插件模式下，Agent 交互面板与代码编辑区、终端面板的空间关系是怎样的？
4. Diff 审查界面（[RFC-0007 Review](../03-RFC/RFC-0007%20Review.md)）作为高频交互点，信息密度如何取舍（一次展示所有变更 vs 分批展示）？
5. 上下文可审查性（[RFC-0002 Context Engine](../03-RFC/RFC-0002%20Context%20Engine.md) §7）在信息架构中是默认可见还是需要用户主动展开？
6. 自主性级别配置（[RFC-0015 Permission](../03-RFC/RFC-0015%20Permission.md)）应该是全局设置、每 Session 设置，还是每个任务粒度设置？

## 建议大纲结构

- **1. 核心信息实体清单**：Session、Task、Plan、Turn、Tool Call、Diff、Context Card 等实体的层级关系图
- **2. CLI 信息架构**：命令树、交互式对话流内的信息分区（计划展示区/执行日志区/确认交互区）
- **3. IDE 插件信息架构**：面板布局、与原生编辑器 UI 的融合点
- **4. 导航模型**：用户如何在"当前任务""历史 Session""设置"之间导航
- **5. 状态可见性设计**：Agent 当前处于什么状态（规划中/执行中/等待确认）在界面上如何时刻可感知，呼应 [Product Philosophy](../00-Vision/Product%20Philosophy.md) 可解释性支柱
- **6. 信息密度分级**：默认简洁视图 vs 可展开的详细视图（trajectory 全量日志、Context Card 来源等）

## 与已有文档的关联

- 是 [User Journey.md](User%20Journey.md) 落地为具体界面结构的中间产物
- 核心信息实体清单应与 [Domain Model.md](../02-Architecture/Domain%20Model.md) 保持一致，避免产品概念和技术模型脱节
- 最终视觉/交互设计产出在 [04-UX](../04-UX/_INDEX.md)

## 完成本章节所需的前置工作

- 需要先确定 CLI 与 IDE 插件在 v1.0 的优先级（见 [Roadmap.md](Roadmap.md)），信息架构设计的起点不同
- 建议先做低保真线框图验证核心信息层级，再进入本文档的正式定稿
