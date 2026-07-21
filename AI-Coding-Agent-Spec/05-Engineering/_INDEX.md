# 05-Engineering — 目录说明

> **状态：🚧 部分展开** — 技术架构和4份详细设计文档已完整，其余规范类文档待编码阶段由工程师产出。

## 已完成的文档

| 文件 | 内容 |
|---|---|
| [Technical Architecture.md](Technical%20Architecture.md) | 技术架构：Java 21 + AgentScope 2.0 + Spring Boot + GraalVM + Maven 6模块 |
| [Database-DDL.md](Database-DDL.md) | 完整 SQLite DDL（7表+向量索引）+ migration策略 + 常用SQL示例 |
| [API-Contract.md](API-Contract.md) | Java DTO完整定义 + Spring Shell CLI命令 + AgentScope 28种Event→TUI映射 |
| [Core-Modules-Class-Design.md](Core-Modules-Class-Design.md) | AgentService + ContextEngineService + GitService 类图 + 方法签名 + 时序图 |
| [Permission-Git-Detail.md](Permission-Git-Detail.md) | CustomPermissionConfig (L1-L4矩阵) + HardConfirmList (7规则) + CommandRiskAnalyzer + JGit BranchManager/CommitService |

## 待由工程师产出的文档

- `Code Standards.md` — Java 代码规范（原型验证后定稿）
- `Testing Strategy.md` — 测试策略（Benchmark 基础设施搭建后定稿）
- `CI/CD.md` — CI/CD 配置（首次 release 时产出）
- `Release Process.md` — 发布流程（首次 release 时产出）
