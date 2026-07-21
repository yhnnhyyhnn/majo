# ADR-0004：CLI 与 IDE 插件开发优先级

## Status

Accepted

## Context

[PRD.md](../01-Product/PRD.md) 将 VS Code 插件列为 P1，CLI 在 P0 范围内但未独立标注为 P0。[Roadmap.md](../01-Product/Roadmap.md)（大纲占位）尚未确定两者的具体开发顺序。[Overall Architecture §2](../02-Architecture/Overall%20Architecture.md#2-整体分层架构) 的分层图中 CLI 和 IDE 并列在客户端层，架构上无先后依赖约束。

影响本决策的关键因素：

1. **对标产品成熟路径**：Claude Code、OpenCode 均以 CLI 起手，在 CLI 上验证核心 Agent 能力后再扩展到 IDE 集成。Cursor 是例外（从 IDE 起手），但 Cursor 的路径是 fork VS Code 做深度定制，工程投入远超一个插件。本产品的第一阶段策略是功能对标而非差异化，应走已被验证的路径。
2. **反馈循环速度**：CLI 的开发-测试-迭代速度远快于 VS Code 插件（不需要调试 Extension Host、不需要跑 VS Code Insiders 测试），在 MVP 阶段更有利于快速验证 Agent 核心能力。
3. **用户可达性**：CLI 零安装额外依赖（用户已经有终端），IDE 插件要求用户安装特定编辑器——CLI 的用户覆盖面更广，尤其是在产品早期验证阶段

## Decision

**v1.0 按 CLI-first 顺序开发：CLI 在 M1 交付核心能力，VS Code 插件在 M2 启动开发。**

具体排期建议（需 [Roadmap.md](../01-Product/Roadmap.md) 完整版确认）：

| 里程碑 | CLI 状态 | IDE 插件状态 | 理由 |
|---|---|---|---|
| M1（可用的 Agentic Loop） | ✅ 完整交付：Agent Runtime + Tool Runtime + Context Engine 基础版 | ⬜ 不做 | 先通过 CLI 验证核心 Agent 能力是否达标，IDE 插件依赖核心能力稳定后再启动 |
| M2（深度上下文 + IDE 集成） | ✅ 持续增强：Context Engine 深化 + Permission 精细化 | ✅ 启动开发：基于 M1 稳定的 Core 接口开发 VS Code 插件 | Core 接口稳定后，IDE 插件的开发风险大幅降低 |
| M3（生态可扩展） | ✅ 持续增强 | ✅ 完整交付 | 此时产品已有用户基础，IDE 插件的体验打磨有真实反馈驱动 |

理由：

1. Claude Code / OpenCode 的路径已被验证：CLI 先验证 Agent 能力的核心价值，IDE 集成是在核心能力稳定后的体验升级，不是替代路径
2. 开发速度：CLI 一个 Sprint 可以出可用原型，VS Code 插件的一个完整开发周期通常需要 2-3 个 Sprint（Extension Host 调试、UI 适配、兼容性测试）
3. 接口设计先收敛再扩展：先通过 CLI 把 Agent Core 的对外接口（API）跑稳定，IDE 插件再基于稳定接口开发——避免"插件开发到一半，Core 接口大改导致插件大量返工"

**备选策略（未采用的理由）**：
- **CLI 和 IDE 插件并行开发**：在 M1 阶段 Core 接口尚未稳定时并行开发两项客户端会导致大量返工，且团队资源有限时不如聚焦单一交付物保证质量
- **IDE 插件优先**：核心价值在 Agent 编排能力，不在编辑器集成——CLI 足以验证这个价值。插件是体验升级，不是价值验证的必需品

## Consequences

### 正面影响

- 产品开发风险更低：先在一个客户端验证 Agent 核心价值，不过早投入 IDE 插件的开发精力
- CLI 的用户覆盖面更广（不需要特定编辑器），更利于 Early Adopter 获取
- Core 接口先稳定，IDE 插件开发时不需要频繁追着 Core 的变更跑

### 负面权衡

- Cursor 已经证明了 IDE 深度集成的体验优势，本产品 v1.0 在 IDE 体验上会落后于 Cursor——但这是 [Mission.md](../00-Vision/Mission.md) 策略中"先对标核心能力"的明知权衡
- 从 CLI 到 IDE 的用户品牌认知可能不一致——用户可能把本产品理解为"CLI 工具"而非"编码 Agent 平台"，需要在 M2 IDE 插件上线时做品牌层面的重新沟通

### 对已有 RFC 的影响

- [RFC-0001 Agent Runtime](../03-RFC/RFC-0001%20Agent%20Runtime.md) 的 §5 接口设计需确保 CLI 和 IDE 插件使用同一套 Agent Core API——当前设计已符合此要求（Agent Runtime 通过统一 API 暴露给客户端层）
- [Roadmap.md](../01-Product/Roadmap.md) 完整版定稿时需按本 ADR 的排期建议填充 M1/M2/M3 的 CLI/IDE 节点
- [04-UX](../04-UX/_INDEX.md) 的 CLI 部分应优先于 IDE 部分启动设计
