## 元信息

| 项 | 值 |
|---|---|
| RFC 编号 | RFC-0018 |
| 标题 | Plugin SDK |
| 状态 | 🚧 大纲占位（Outline Only） |
| 关联 PRD | [Vision 五年愿景-生态而非孤岛](../00-Vision/Vision.md#五年后的样子) |
| 关联架构 | [Overall Architecture](../02-Architecture/Overall%20Architecture.md#3-模块职责总览) |
| 依赖 RFC | [RFC-0003 Tool Runtime](RFC-0003%20Tool%20Runtime.md)、[RFC-0009 MCP](RFC-0009%20MCP.md)、[RFC-0002 API](../02-Architecture/API.md) |

## 1. 背景与目标（待细化）

Plugin SDK 让第三方开发者能够为本产品扩展能力，是 [Vision.md](../00-Vision/Vision.md)"生态而非孤岛"五年愿景的具体实现路径，直接支撑 [Mission.md](../00-Vision/Mission.md) M3 里程碑"生态可扩展"。

## 2. 本RFC需要回答的核心设计问题

1. Plugin SDK 与 MCP（[RFC-0009](RFC-0009%20MCP.md)）的关系是什么——MCP 是"接入外部工具"的标准协议，Plugin SDK 是否是更深度的"扩展产品本身行为"（如自定义 UI 面板、自定义 Permission 规则）能力？两者边界如何划分避免概念混淆？
2. 插件的能力边界如何设计——是否允许插件介入 Agent Runtime 的核心决策循环（如自定义 Reflection 逻辑），还是仅限于工具/UI 层扩展？
3. 插件的权限模型是什么，如何避免恶意/低质量插件影响核心产品的稳定性和安全性（呼应 [Security.md](../02-Architecture/Security.md)）？
4. SDK 的 API 稳定性承诺如何设计，避免核心产品快速迭代导致插件生态频繁破坏性变更？
5. 插件的分发与发现机制是否需要独立的 Marketplace（[RFC-0020](RFC-0020%20Marketplace.md)），还是先支持手动安装？

## 3. 建议章节结构

- 核心概念（Plugin/Extension Point/Capability）
- 与 MCP 的边界划分
- 扩展点清单（工具/UI/Permission 规则等）
- 插件权限与沙箱隔离模型
- API 稳定性与版本管理策略
- 分发机制（v1.0 手动安装 vs 未来 Marketplace）
- 风险与缓解
- 验收标准
- 开放问题

## 4. 已知的关键设计张力

- **扩展能力开放度 vs 核心产品稳定性**：允许插件深度介入核心逻辑能释放更大生态价值，但也带来更大的稳定性和安全风险，需要谨慎设计"够用但不过度开放"的扩展点集合
- **与 MCP 的功能重叠**：如果 Plugin SDK 能力设计不当，可能与 MCP 产生大量功能重叠，造成"两套扩展机制并存"的生态碎片化，需要在架构上明确分工

## 5. 前置依赖

- [RFC-0009 MCP](RFC-0009%20MCP.md) 需先完整落地并验证，明确哪些扩展需求 MCP 无法满足，再定义 Plugin SDK 的必要性边界
- 属于 [Mission.md](../00-Vision/Mission.md) M3 阶段范畴，v1.0（M1/M2）不需要启动此 RFC 的详细设计
