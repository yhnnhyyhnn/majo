## 元信息

| 项 | 值 |
|---|---|
| RFC 编号 | RFC-0020 |
| 标题 | Marketplace |
| 状态 | 🚧 大纲占位（Outline Only） |
| 关联 PRD | [P2 明确排除](../01-Product/PRD.md#3-产品范围本阶段-p0p1p2) |
| 关联架构 | [Overall Architecture](../02-Architecture/Overall%20Architecture.md#3-模块职责总览) |
| 依赖 RFC | [RFC-0018 Plugin SDK](RFC-0018%20Plugin%20SDK.md)、[RFC-0009 MCP](RFC-0009%20MCP.md) |

## 1. 背景与目标（待细化）

**本 RFC 仅做架构预留讨论，不构成 v1.0 交付范围。** Marketplace 是插件/MCP Server 的发现与分发平台，[PRD.md](../01-Product/PRD.md) 明确将其列为 P2 排除项。其价值依赖于 Plugin SDK（[RFC-0018](RFC-0018%20Plugin%20SDK.md)）生态达到一定规模后才有意义，属于 [Mission.md](../00-Vision/Mission.md) M3 之后的范畴。

## 2. 本RFC需要回答的核心设计问题

1. Marketplace 收录的对象是什么——自研 Plugin SDK 插件、第三方 MCP Server，还是两者都包括？如何统一呈现给用户避免概念混乱？
2. 插件/Server 的质量与安全审核机制是什么（呼应 [RFC-0009 §2](RFC-0009%20MCP.md#2-本rfc需要回答的核心设计问题)"恶意 MCP Server"风险，Marketplace 是否应该承担审核把关角色）？
3. 是否需要商业化分成机制（付费插件），这对 [07-Operation](../07-Operation/_INDEX.md) 的定价体系有何影响？
4. Marketplace 的发现机制如何设计（分类浏览、搜索、评分评价系统）？
5. 是否需要与已有的 MCP 生态（如果社区已经形成事实上的 MCP Server 目录）互通，而非从零建立孤立生态？

## 3. 建议章节结构

- 背景：为什么 v1.0 不做，触发条件（Plugin SDK 生态成熟度信号、用户需求信号）
- 收录范围界定（自研插件 vs MCP Server）
- 审核与信任机制设计
- 商业化模式探讨（是否分成）
- 发现与检索机制
- 与外部已有 MCP 生态的关系
- 明确的非目标（v1.0）
- 开放问题

## 4. 已知的关键设计张力

- **自建生态 vs 借力已有生态**：从零建立 Marketplace 需要巨大的冷启动投入，而 MCP 协议本身是开放的，可能存在借力社区已有的 MCP Server 目录而非重复造轮子的更优路径，需要审慎评估
- **审核质量 vs 生态开放速度**：严格审核提升安全性但拖慢生态增长速度，宽松审核加速增长但放大安全风险，两者权衡需要根据实际用户规模动态调整

## 5. 前置依赖

- [RFC-0018 Plugin SDK](RFC-0018%20Plugin%20SDK.md) 需先落地并有一定数量的真实插件产出，Marketplace 的设计才有真实素材可参考，而非凭空设计
- 属于 [Mission.md](../00-Vision/Mission.md) M3 之后的范畴，当前不应投入详细设计精力
