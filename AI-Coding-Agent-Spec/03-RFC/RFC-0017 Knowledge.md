## 元信息

| 项 | 值 |
|---|---|
| RFC 编号 | RFC-0017 |
| 标题 | Knowledge |
| 状态 | 🚧 大纲占位（Outline Only） |
| 关联 PRD | [P0-2 延伸](../01-Product/PRD.md#3-产品范围本阶段-p0p1p2) |
| 关联架构 | [Overall Architecture](../02-Architecture/Overall%20Architecture.md#3-模块职责总览) |
| 依赖 RFC | [RFC-0002 Context Engine](RFC-0002%20Context%20Engine.md)、[RFC-0005 Memory](RFC-0005%20Memory.md) |

## 1. 背景与目标（待细化）

Knowledge 负责项目文档（README、架构文档、ADR、Wiki）的检索与利用，是 Context Engine（[RFC-0002](RFC-0002%20Context%20Engine.md)，专注代码本身）的补充——很多项目决策的"为什么"记录在文档而非代码中，Agent 需要能利用这部分知识做出更符合项目历史决策的判断。

## 2. 本RFC需要回答的核心设计问题

1. Knowledge 的数据来源边界是什么——仓库内文档（README/docs 目录）、外部知识库（Confluence/Notion，需要额外集成）、还是仅限本地文件？
2. Knowledge 检索是否复用 [RFC-0002 Context Engine](RFC-0002%20Context%20Engine.md) 的语义检索基础设施，还是需要独立的索引与检索管线（文档的分块策略与代码不同，通常按标题层级而非函数边界）？
3. 项目内的 ADR（[08-ADR](../08-ADR/_INDEX.md) 类文档）作为"历史决策记录"，如何被 Agent 识别并在相关任务中主动引用（例如用户要求做某个已被 ADR 否决过的方案时，Agent 能否提醒）？
4. Knowledge 与 Memory（[RFC-0005](RFC-0005%20Memory.md)）的边界如何划分——Knowledge 是"项目内显式存在的文档"，Memory 是"从交互中隐式学习的偏好"，两者检索时如何协同排序？
5. 外部知识库集成是否应该通过 MCP 协议实现（[RFC-0009](RFC-0009%20MCP.md)），而不是在 Knowledge 内部硬编码特定第三方系统的对接逻辑？

## 3. 建议章节结构

- 核心概念（KnowledgeSource/DocumentChunk）
- 数据来源范围界定（v1.0 仅本地仓库文档 vs 外部知识库）
- 与 Context Engine 检索基础设施的复用关系
- ADR/决策记录的特殊识别与引用机制
- 与 Memory 的边界与协同排序
- 外部知识库集成路径（是否走 MCP）
- 验收标准
- 开放问题

## 4. 已知的关键设计张力

- **独立索引 vs 复用 Context Engine**：文档和代码的语义分块策略天然不同，独立实现更精确但增加系统复杂度，复用现有基础设施更简洁但可能牺牲检索质量，需要基于实际效果验证取舍
- **知识时效性 vs 检索稳定性**：项目文档可能滞后于代码实际状态（过时的 README），Agent 如果盲目信任文档知识可能得出错误结论，需要设计某种"知识可信度"标注机制

## 5. 前置依赖

- [RFC-0002 Context Engine](RFC-0002%20Context%20Engine.md) 完整版定稿后，才能确定是否具备复用其检索基础设施的条件
- 建议 v1.0 范围内 Knowledge 仅做最小化实现（本地 README/docs 检索），外部知识库集成留待 P1+
