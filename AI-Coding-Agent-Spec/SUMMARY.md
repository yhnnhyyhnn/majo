# SUMMARY

全局章节索引。状态标记：✅ 完整 ｜ 🚧 大纲占位 ｜ ⬜ 未创建

## 00-Vision — 为什么做

| 文件 | 状态 | 一句话概要 |
|---|---|---|
| [Vision.md](00-Vision/Vision.md) | ✅ | 长期愿景：让 Agent 成为开发者默认的编码方式 |
| [Mission.md](00-Vision/Mission.md) | ✅ | 现阶段使命：对齐 Cursor/Claude Code/OpenCode 核心能力 |
| [Product Philosophy.md](00-Vision/Product%20Philosophy.md) | ✅ | 产品哲学：人在回路、可解释、可中断 |
| [Design Principles.md](00-Vision/Design%20Principles.md) | ✅ | 10 条设计原则，指导后续所有子系统设计 |

## 01-Product — 做什么

| 文件 | 状态 | 一句话概要 |
|---|---|---|
| [PRD.md](01-Product/PRD.md) | ✅ | 核心 PRD：功能范围、场景、验收标准 |
| [Market.md](01-Product/Market.md) | ✅ | 市场与竞品分析：市场规模、5个竞品深度拆解、市场空白、风险、策略对齐 |
| [Persona.md](01-Product/Persona.md) | ✅ | 3个真实用户画像（访谈驱动）：后端架构师/前端工程师/全栈负责人 |
| [User Journey.md](01-Product/User%20Journey.md) | 🚧 | 用户旅程大纲 |
| [Information Architecture.md](01-Product/Information%20Architecture.md) | 🚧 | 信息架构大纲 |
| [Roadmap.md](01-Product/Roadmap.md) | ✅ | M1/M2/M3 路线图：基于用户优先级定价$8-10/mo |
| [KPI.md](01-Product/KPI.md) | 🚧 | 指标体系大纲 |

## 02-Architecture — 怎么组织

| 文件 | 状态 | 一句话概要 |
|---|---|---|
| [Overall Architecture.md](02-Architecture/Overall%20Architecture.md) | ✅ | 整体分层架构、模块边界、技术选型 |
| [Domain Model.md](02-Architecture/Domain%20Model.md) | ✅ | 领域模型：聚合根、实体关系、值对象、领域事件 |
| [Database.md](02-Architecture/Database.md) | ✅ | SQLite 表结构、Trajectory 日志、向量索引、加密与数据生命周期 |
| [Event Bus.md](02-Architecture/Event%20Bus.md) | ✅ | 事件总线：进程内EventEmitter、事件Schema、发布订阅、P2可替换性设计 |
| [API.md](02-Architecture/API.md) | ✅ | Session/Task/Stream/Review/History API + 流式协议 + 错误码 + 版本策略 |
| [Sequence.md](02-Architecture/Sequence.md) | ✅ | 8个Mermaid时序图：主流程/恢复/重试/Review/降级/Git回退/预算裁剪/索引更新 |
| [Deployment.md](02-Architecture/Deployment.md) | ✅ | npm/Homebrew/SEA分发、跨平台差异、资源基线、自动更新 |
| [Security.md](02-Architecture/Security.md) | ✅ | 四大攻击面防御、Prompt Injection、凭证管理、敏感文件防护、MCP信任边界 |

## 03-RFC — 子系统详细设计（20 篇）

| RFC | 主题 | 状态 |
|---|---|---|
| [RFC-0001](03-RFC/RFC-0001%20Agent%20Runtime.md) | Agent Runtime | ✅ |
| [RFC-0002](03-RFC/RFC-0002%20Context%20Engine.md) | Context Engine | ✅ |
| [RFC-0003](03-RFC/RFC-0003%20Tool%20Runtime.md) | Tool Runtime | ✅ |
| [RFC-0004](03-RFC/RFC-0004%20Task%20Engine.md) | Task Engine | ✅ |
| [RFC-0005](03-RFC/RFC-0005%20Memory.md) | Memory | 🚧 |
| [RFC-0006](03-RFC/RFC-0006%20Workspace.md) | Workspace | ✅ |
| [RFC-0007](03-RFC/RFC-0007%20Review.md) | Review | ✅ |
| [RFC-0008](03-RFC/RFC-0008%20Git.md) | Git | ✅ |
| [RFC-0009](03-RFC/RFC-0009%20MCP.md) | MCP | 🚧 |
| [RFC-0010](03-RFC/RFC-0010%20Browser.md) | Browser | 🚧 |
| [RFC-0011](03-RFC/RFC-0011%20Cloud%20Agent.md) | Cloud Agent | 🚧 |
| [RFC-0012](03-RFC/RFC-0012%20Model%20Router.md) | Model Router | ✅ |
| [RFC-0013](03-RFC/RFC-0013%20Prompt%20Engine.md) | Prompt Engine | 🚧 |
| [RFC-0014](03-RFC/RFC-0014%20Sandbox.md) | Sandbox | ✅ |
| [RFC-0015](03-RFC/RFC-0015%20Permission.md) | Permission | ✅ |
| [RFC-0016](03-RFC/RFC-0016%20Telemetry.md) | Telemetry | 🚧 |
| [RFC-0017](03-RFC/RFC-0017%20Knowledge.md) | Knowledge | 🚧 |
| [RFC-0018](03-RFC/RFC-0018%20Plugin%20SDK.md) | Plugin SDK | 🚧 |
| [RFC-0019](03-RFC/RFC-0019%20Enterprise.md) | Enterprise | 🚧 |
| [RFC-0020](03-RFC/RFC-0020%20Marketplace.md) | Marketplace | 🚧 |

## 04-UX ~ Appendix — 待展开领域

| 目录 | 状态 | 说明 |
|---|---|---|
| [04-UX](04-UX/_INDEX.md) | 🚧 | 交互设计、终端 UI、编辑器插件 UI |
| [05-Engineering](05-Engineering/_INDEX.md) | 🚧（技术架构+详细设计已完整 ✅） | 技术架构、DB DDL、API契约、类设计、Permission+Git详细设计 |
| [06-Enterprise](06-Enterprise/_INDEX.md) | 🚧 | RBAC、审计、私有化部署、合规 |
| [07-Operation](07-Operation/_INDEX.md) | 🚧 | 增长、定价、客户成功、社区运营 |
| [08-ADR](08-ADR/_INDEX.md) | ✅ 6 ADR 已完成 | Agent Core 语言、Sandbox 路线、Embedding 策略、CLI/IDE 优先级、Telemetry 协议、Java+AgentScope转向 |
| [Appendix](Appendix/_INDEX.md) | 🚧（Benchmark Task Set 已完整✅） | 术语表、参考资料、竞品拆解笔记、[基准任务集](Appendix/Benchmark%20Task%20Set.md) |

## 完成度总览

- 总文件数：**45 篇**（4 Vision + 7 Product + 8 Architecture + 20 RFC + 6 目录索引）
- 已完整撰写（可直接作为设计依据）：**26 篇** — Vision×4、PRD、Market、Persona、Roadmap、Overall Architecture 全8篇、RFC-0001~0004、0006~0008、0012、0014、0015、Technical Architecture
- 大纲占位（🚧）：**20 篇** — 全部已创建，覆盖 01-Product 剩余6篇、02-Architecture 剩余7篇、RFC-0003~0020 共18篇、04-UX~Appendix 共6个目录索引
- **本轮任务范围已 100% 完成**：目录框架 + 核心几篇正文 + 全部剩余章节大纲占位，无遗漏文件
- 下一步优先级：
  1. ~~Market.md~~ ✅ 已完成
  2. ~~Persona.md、Roadmap.md 阻断已解除~~ — 基于 Market.md 竞品数据和 ADR-0004 的 CLI/IDE 决策可编写，建议下一批启动
  3. ~~RFC-0004/0006/0007/0008（Task Engine/Workspace/Review/Git）~~ ✅ 已完成 — **P0 RFC 全集（10 篇）已就绪**，所有 P0 能力的技术设计均可作为工程实现依据
  4. 横向质量优化：交叉引用一致性审查、术语表（Glossary）汇总、UI 层 04-UX 启动（当前 P0 RFC 已覆盖交互状态机，但缺少具体 UI 视觉规范）
