## 元信息

| 项 | 值 |
|---|---|
| RFC 编号 | RFC-0009 |
| 标题 | MCP |
| 状态 | 🚧 大纲占位（Outline Only） |
| 关联 PRD | [P1-2](../01-Product/PRD.md#3-产品范围本阶段-p0p1p2) |
| 关联架构 | [Overall Architecture](../02-Architecture/Overall%20Architecture.md#3-模块职责总览) |
| 依赖 RFC | [RFC-0003 Tool Runtime](RFC-0003%20Tool%20Runtime.md)、[RFC-0015 Permission](RFC-0015%20Permission.md) |

## 1. 背景与目标（待细化）

MCP（Model Context Protocol）客户端支持接入第三方 MCP Server，动态扩展 Agent 的工具集，直接实现 [PRD P1-2](../01-Product/PRD.md) 和 [Design Principles](../00-Vision/Design%20Principles.md) 原则 9"协议优先于硬编码"。这是 Cursor/Claude Code/OpenCode 共同验证过的生态扩展路径。

## 2. 本RFC需要回答的核心设计问题

1. MCP Server 的连接管理策略是什么——启动时静态加载配置的 Server 列表，还是支持运行时动态添加/移除？
2. MCP 协议本身定义了 stdio/SSE 等传输方式，本产品需要支持哪些传输方式的完整覆盖？
3. MCP 工具的元数据（描述、参数 Schema）如何转换为 Tool Runtime（[RFC-0003](RFC-0003%20Tool%20Runtime.md)）统一接口所需的格式？
4. 第三方 MCP Server 是不受信代码，如何设计信任边界（呼应 [Security.md](../02-Architecture/Security.md) 威胁模型中的"恶意 MCP Server"风险）？
5. MCP 工具调用失败/超时的处理策略与内置工具是否需要差异化（外部依赖的可靠性天然更低）？
6. 是否需要维护一个"推荐/验证过的 MCP Server 列表"，帮助用户在使用未知第三方 Server 前做出知情判断？
7. MCP Server 的配置（如需要的 API Key/环境变量）如何与本产品的凭证管理集成，避免用户重复配置？

## 3. 建议章节结构

- 核心概念（MCP Server/MCP Tool/Transport）
- 连接生命周期管理
- 协议适配层（MCP Schema → Tool Runtime 统一接口）
- 信任边界与权限隔离设计
- 配置与凭证管理
- 已知问题：不受信第三方代码的风险与缓解
- 验收标准
- 开放问题

## 4. 已知的关键设计张力

- **生态开放性 vs 安全性**：MCP 的价值在于开放生态，但开放意味着无法完全控制第三方 Server 的行为，需要在"允许灵活接入"和"默认安全边界"间取得平衡
- **协议标准兼容性 vs 产品体验优化**：严格遵循 MCP 协议规范保证生态互通性，但可能限制针对本产品场景的体验优化空间

## 5. 前置依赖

- [RFC-0003 Tool Runtime](RFC-0003%20Tool%20Runtime.md) 需先确定统一工具接口设计
- [RFC-0015 Permission](RFC-0015%20Permission.md) 需先确定外部工具的风险分级默认策略
