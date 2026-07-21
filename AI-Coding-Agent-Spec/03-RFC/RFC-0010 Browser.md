## 元信息

| 项 | 值 |
|---|---|
| RFC 编号 | RFC-0010 |
| 标题 | Browser |
| 状态 | 🚧 大纲占位（Outline Only） |
| 关联 PRD | [P1-4](../01-Product/PRD.md#3-产品范围本阶段-p0p1p2) |
| 关联架构 | [Overall Architecture](../02-Architecture/Overall%20Architecture.md#3-模块职责总览) |
| 依赖 RFC | [RFC-0003 Tool Runtime](RFC-0003%20Tool%20Runtime.md)、[RFC-0014 Sandbox](RFC-0014%20Sandbox.md) |

## 1. 背景与目标（待细化）

浏览器工具为前端开发场景提供截图、DOM 检查等能力，让 Agent 能够"看到"页面实际渲染效果、调试前端 bug，是 [PRD P1-4](../01-Product/PRD.md) 范围的能力。v1.0 不强制交付，但工具接口设计需要考虑未来接入路径。

## 2. 本RFC需要回答的核心设计问题

1. 浏览器工具是基于 Headless 浏览器自动化（如 Playwright/Puppeteer）本地驱动，还是通过 MCP Server 形式接入（与 [RFC-0009](RFC-0009%20MCP.md) 的关系）？
2. 截图/DOM 快照如何注入到 LLM 上下文——多模态模型直接理解截图，还是需要转换为结构化 DOM 描述文本？
3. 浏览器工具涉及启动本地开发服务器（如 `npm run dev`）、访问用户可能未预期的网络资源，如何设计权限边界（呼应 [Design Principles](../00-Vision/Design%20Principles.md) 原则 6 沙箱缺省）？
4. 是否需要支持交互式操作（点击、输入表单）用于端到端调试，还是 v1.0 仅做只读的截图/检查？
5. 浏览器工具的资源开销（启动 Headless 浏览器实例）如何与 Sandbox 生命周期管理协同，避免资源泄露？

## 3. 建议章节结构

- 核心概念（BrowserSession/Screenshot/DOMSnapshot）
- 技术选型方向（Headless 浏览器自动化框架对比）
- 与多模态模型的集成方式
- 权限与沙箱边界设计
- 只读检查 vs 交互式操作的范围界定
- 资源生命周期管理
- 验收标准
- 开放问题

## 4. 已知的关键设计张力

- **能力完整性 vs 交付复杂度**：完整的交互式浏览器自动化能力强大但实现复杂、资源开销大；v1.0 建议先做最小可用的只读截图/DOM检查，交互式操作留待后续
- **本地网络访问 vs 安全边界**：浏览器工具天然需要访问本地开发服务器甚至外部网络，与"沙箱缺省"原则如何协调需要专门设计

## 5. 前置依赖

- [RFC-0014 Sandbox](RFC-0014%20Sandbox.md) 需先确定网络访问的隔离策略
- 建议先确认这是 P1 而非 P0，避免过早投入影响核心能力交付节奏（见 [Roadmap.md](../01-Product/Roadmap.md)）
