# ADR-0001：Agent Core 实现语言选型

## Status

Accepted

## Context

[Overall Architecture §6](../02-Architecture/Overall%20Architecture.md#6-技术选型方向待细化非最终决策) 列出两个候选方向：**TypeScript/Node.js**（生态对齐 VS Code 插件）和 **Go**（性能/二进制分发）。多个 RFC 的伪代码示例使用 TypeScript 语法，但尚未做出正式决策。

影响本决策的关键因素：

1. **产品形态**（[PRD.md](../01-Product/PRD.md)）：CLI-first，配套 VS Code 插件（P1）。VS Code Extension API 本身就是 TypeScript/JavaScript——如果用 Go，插件必须通过子进程/socket 与 Core 通信，增加架构复杂度和延迟。
2. **生态对齐**：MCP Server 参考实现多为 TypeScript/Python，Context Engine 的 Tree-sitter 解析器、大多数 AI SDK（OpenAI/Anthropic 官方库）以 TypeScript/JS 为一等公民。Go 的 AI SDK 生态相对不成熟。
3. **分发**：Go 的优势是编译为单二进制，无需运行时依赖。但 Node.js 的单文件分发（pkg/nexe/sea）已足够成熟，且目标用户（专业开发者）几乎都预装了 Node.js。
4. **性能**：Agent Core 本身是 I/O 密集型（等待 LLM API 响应、文件系统读写），不是 CPU 密集型。Node.js 的事件驱动模型对此类工作负载没有性能劣势。
5. **团队技能**：如果团队有 Go 或 TypeScript 的技能偏向，最终应以团队实际构成做最终修正——本 ADR 仅基于技术因素给出建议方向。

## Decision

**选用 TypeScript，Node.js 运行时。**

理由：

1. VS Code 插件天然需要 TypeScript——如果 Core 也是 TypeScript，插件可以共享类型定义和部分逻辑（如 Permission Decision 的类型结构），不需要跨进程序列化
2. TypeScript 的类型系统为 Agent 内部的复杂状态传递（UnifiedRequest、Observation、ToolCall 等）提供了编译期安全网——Go 虽然有静态类型但缺乏 TypeScript 的联合类型（discriminated union）表达能力，这在状态机设计中很有价值
3. AI SDK 生态以 TypeScript/JS 为一等公民，接入新 Provider 时无需维护自研 SDK 封装
4. CLI 二进制分发可通过 `pkg` 或 Node.js 22+ 的 Single Executable Applications (SEA) 方案解决——这些工具已经足够成熟
5. 本规范中所有 RFC 伪代码示例已经选择了 TypeScript 语法，保持了技术一致性

**备选策略（未采用的理由）**：
- **Go** 的二进制分发确实更优雅（单文件、无依赖），但这个优势在我们衡量因素中权重不及 VS Code 插件生态对齐和 AI SDK 成熟度
- **Rust** 未被考虑——生态差距太大，AI SDK 几乎不存在

## Consequences

### 正面影响

- VS Code 插件（P1）可以与 Core 共享类型定义和工具调用数据结构，减少跨进程通信的序列化开销
- 接入新的 LLM Provider 可以使用现有的官方 TypeScript SDK，不需要维护自研 HTTP 封装层
- Tree-sitter（Context Engine 的 AST 解析依赖）的 JS 绑定成熟，本地 JS 解析器也有大量可选方案

### 负面权衡

- 二进制分发需要依赖打包工具（`pkg`/SEA），而非原生的单文件编译输出——但这是可接受的额外构建步骤
- Node.js 内存占用基线高于 Go 编译的二进制（~50MB vs ~10MB），对性能敏感的用户场景（如 8GB 内存的老机器）可能有影响——但本产品定位的专业开发者通常拥有更高配置
- 长期运行场景下 Node.js 的事件循环阻塞风险高于 Go 的 goroutine 模型——但 Agent Core 的并发模型主要是管理少量 LLM 请求和工具调用，不是高并发 HTTP 服务器

### 对已有 RFC 的影响

- 不影响任何 RFC 的核心设计——所有 RFC 的伪代码示例已经是 TypeScript 语法，无需修正
- [Deployment.md](../02-Architecture/Deployment.md) 的分发机制需要确认使用 `pkg` 还是 SEA 方案——建议在实施阶段做原型验证后通过 ADR-0006 决定更具体方案
- [05-Engineering](../05-Engineering/_INDEX.md) 的代码规范、CI/CD 将围绕 TypeScript/Node.js 生态设计
