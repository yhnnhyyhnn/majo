## 元信息

| 项 | 值 |
|---|---|
| RFC 编号 | RFC-0013 |
| 标题 | Prompt Engine |
| 状态 | 🚧 大纲占位（Outline Only） |
| 关联 PRD | [P0-1](../01-Product/PRD.md#3-产品范围本阶段-p0p1p2) |
| 关联架构 | [Overall Architecture](../02-Architecture/Overall%20Architecture.md#3-模块职责总览) |
| 依赖 RFC | [RFC-0002 Context Engine](RFC-0002%20Context%20Engine.md)、[RFC-0003 Tool Runtime](RFC-0003%20Tool%20Runtime.md)、[RFC-0012 Model Router](RFC-0012%20Model%20Router.md) |

## 1. 背景与目标（待细化）

Prompt Engine 负责 System Prompt 组装、工具描述生成、Context Card 注入格式化，是连接 Context Engine（上下文来源）、Tool Runtime（工具描述来源）与 Model Router（最终发送）之间的组装层。

## 2. 本RFC需要回答的核心设计问题

1. System Prompt 的模板结构如何设计，哪些部分是固定的（产品身份、行为准则），哪些是动态注入的（当前 Session 状态、可用工具列表）？
2. 工具描述（Tool Schema 转文本/结构化描述）如何生成，是否需要针对不同 Provider 的最佳实践做差异化调整？
3. Context Card（[RFC-0002 §6](RFC-0002%20Context%20Engine.md#6-context-window-budget-分配)）如何格式化注入 Prompt，是否需要保留来源标注（文件路径、匹配来源）供 LLM 参考？
4. Prompt 模板是否需要支持版本管理和 A/B 测试，以便持续优化 Agent 的任务完成质量？
5. 长 Session 的对话历史如何摘要压缩后注入（避免每轮都携带全部历史导致 token 爆炸）？
6. 不同任务类型（功能实现/Bug修复/代码问答，对应 [PRD 场景 A-D](../01-Product/PRD.md#4-核心用户场景详细旅程见-user-journeymd-大纲)）是否需要不同的 Prompt 模板/行为准则侧重？

## 3. 建议章节结构

- 核心概念（PromptTemplate/SystemPrompt/ToolDescriptor）
- System Prompt 组装流程
- 工具描述生成规范
- Context Card 注入格式
- 对话历史摘要压缩策略
- 模板版本管理
- 与 Model Router 的接口（是否需要针对不同 Provider 调整格式）
- 验收标准
- 开放问题

## 4. 已知的关键设计张力

- **模板通用性 vs Provider 特定优化**：通用模板便于维护，但某些 Provider 对特定 Prompt 结构响应质量更好，过度通用化可能牺牲输出质量
- **历史摘要压缩 vs 信息保真度**：压缩对话历史节省 token，但可能丢失后续任务判断所需的关键细节，压缩策略需要谨慎设计并可验证

## 5. 前置依赖

- [RFC-0002 Context Engine](RFC-0002%20Context%20Engine.md) 的 Context Card 结构需先确定
- [RFC-0012 Model Router](RFC-0012%20Model%20Router.md) 的工具调用格式统一抽象需先确定，Prompt Engine 生成的工具描述需与之对齐
