# AI Coding Agent Product Specification

> **一个对标 Cursor / Claude Code / OpenCode / OpenHands / Codex 的交互式 AI 编程助手产品规范仓库。**

## 这是什么

这是《AI Coding Agent Product Specification v1.0》的源仓库。目标是给出一套**可以直接指导产品、架构、研发、测试和商业化落地**的完整规范，而不是一份停留在概念层面的 PRD。

**产品形态**：交互式 IDE / CLI 工具，用户在本地终端或编辑器插件中与 Agent 实时对话，Agent 读写本地工作区文件、执行命令、调用工具，完成编码任务。对标 Cursor（IDE 深度集成）、Claude Code / OpenCode（CLI-first）、Codex（云端+本地混合）。

**当前阶段**：功能对标阶段。暂无差异化定位，先把 Cursor/Claude Code/OpenCode 已验证的核心能力想清楚、写清楚，差异化留待后续迭代。

## 仓库状态

本仓库处于**框架搭建 + 核心章节**阶段：

- ✅ 完整目录结构已搭建
- ✅ 核心文档已完整撰写（Vision、PRD、Overall Architecture、RFC-0001 Agent Runtime、RFC-0002 Context Engine）
- 🚧 其余章节为**大纲占位**（标注 `[STUB]`），列出该章节应包含的核心问题清单，供后续迭代填充
- 📋 详见每个目录下的 `_INDEX.md` 了解该目录的完成状态

**如何阅读本仓库**：
1. 先读本文件和 [SUMMARY.md](SUMMARY.md) 建立全局认知
2. 按 `00 → 01 → 02 → 03` 的顺序阅读——Vision 回答"为什么做"，Product 回答"做什么"，Architecture 回答"怎么组织"，RFC 回答"每个子系统具体怎么设计"
3. 已完整撰写的文档可直接作为设计依据；`[STUB]` 文档仅作为待办清单，不可直接引用其结论

## 目录结构

```text
AI-Coding-Agent-Spec/
│
├── README.md                    # 本文件
├── SUMMARY.md                   # 全局章节索引 + 完成度追踪
│
├── 00-Vision/                   # 为什么做这个产品
│   ├── Vision.md                ✅ 完整
│   ├── Mission.md               ✅ 完整
│   ├── Product Philosophy.md    ✅ 完整
│   └── Design Principles.md     ✅ 完整
│
├── 01-Product/                  # 产品定义
│   ├── PRD.md                   ✅ 完整（核心）
│   ├── Market.md                🚧 大纲
│   ├── Persona.md               🚧 大纲
│   ├── User Journey.md          🚧 大纲
│   ├── Information Architecture.md 🚧 大纲
│   ├── Roadmap.md               🚧 大纲
│   └── KPI.md                   🚧 大纲
│
├── 02-Architecture/              # 系统架构
│   ├── Overall Architecture.md  ✅ 完整（核心）
│   ├── Domain Model.md          🚧 大纲
│   ├── Database.md              🚧 大纲
│   ├── Event Bus.md             🚧 大纲
│   ├── API.md                   🚧 大纲
│   ├── Sequence.md              🚧 大纲
│   ├── Deployment.md            🚧 大纲
│   └── Security.md              🚧 大纲
│
├── 03-RFC/                       # 20 个子系统设计文档
│   ├── RFC-0001 Agent Runtime.md   ✅ 完整（核心）
│   ├── RFC-0002 Context Engine.md  ✅ 完整（核心）
│   ├── RFC-0003~0020              🚧 大纲（各文件内含完成清单）
│
├── 04-UX/                        # 交互设计（🚧 目录说明）
├── 05-Engineering/                # 工程规范（🚧 目录说明）
├── 06-Enterprise/                 # 企业级能力（🚧 目录说明）
├── 07-Operation/                  # 运营/增长（🚧 目录说明）
├── 08-ADR/                        # 架构决策记录（🚧 目录说明）
└── Appendix/                      # 附录（🚧 目录说明）
```

## 参考对标产品

| 产品 | 形态 | 核心特点 | 本规范借鉴点 |
|---|---|---|---|
| **Cursor** | IDE (VS Code fork) | 深度编辑器集成、Tab 补全、Composer 多文件编辑 | IDE 集成模式、上下文自动收集 |
| **Claude Code** | CLI | Agentic loop、工具调用、Plan mode | Agent Runtime 设计、权限模型 |
| **OpenCode** | CLI (开源) | 多 Provider、Session 管理、Sub-agent 编排 | 多 Agent 编排、Provider 抽象 |
| **OpenHands** | 云端 + 本地 | Docker sandbox、事件驱动架构 | Sandbox/Tool Runtime 隔离设计 |
| **Codex (OpenAI)** | 云端 + CLI | 云端异步任务、本地 CLI 双模式 | 云端 Agent 与本地 Agent 协同 |

## 术语约定

| 术语 | 定义 |
|---|---|
| **Agent** | 由 LLM 驱动、可自主规划并调用工具完成任务的执行单元 |
| **Session** | 一次连续的用户-Agent 交互上下文，包含消息历史、工作区状态 |
| **Tool** | Agent 可调用的能力单元（文件读写、命令执行、搜索等） |
| **Context Engine** | 负责收集、压缩、注入相关代码上下文给 LLM 的子系统 |
| **Workspace** | Agent 操作的本地项目目录 |
| **MCP** | Model Context Protocol，外部工具/数据源接入协议 |

## 贡献与迭代

本仓库遵循**框架先行、逐章补全**的写作策略：
1. 每个 `[STUB]` 文档已列出该章节的核心问题清单
2. 补全时需先读 `00-Vision` 确保设计一致性
3. 涉及子系统设计变更，先过 `08-ADR` 记录决策再改 RFC
4. 所有图表使用 Mermaid，保证可维护、可 diff
