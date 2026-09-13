# ADR-0007：MCP 配置面接入 agent 运行时（MCP Runtime Bridge）

## Status

Accepted（2026-09-13）

## Context

majo 对标 QwenPaw 2.1.0b1 移植时，完整复刻了 MCP 的**配置面**：`/api/mcp` 的客户端卡片 CRUD、`http_timeout` 超时、OAuth 授权（RFC 9728/8414）、工具白名单、访问策略（allow/ask/deny）。但差距分析（对照 QwenPaw 2.2.2b1，442 个提交）发现一个更根本的缺口：

**MCP 配置从未接入 agent 运行时。** AgentScope harness 提供了 `McpServerRegistrar.register(Toolkit, Map<String, McpServerConfig>)`，但 majo 的 6 个 `HarnessAgent.builder()` 构建点都只传入了静态 Spring `Toolkit` bean（`AgentConfig#toolkit`），没有任何代码调用注册器。结果是：用户在界面上配置的 MCP 服务器对 agent 完全不可见——白名单、策略、OAuth 全部是死配置。QwenPaw 侧同期还强化了运行时语义（per-tool 白名单在运行时路径强制 #7504、工具名必须字母开头 #6561），这些都需要先有桥接才有落点。

### 设计张力

1. **共享 Toolkit vs per-agent Toolkit**：majo 的 `Toolkit` 是单例 Spring bean，被全部 6 个 agent 构建点共享；MCP 卡片存储（`McpStore.cardsDir()`）是全局的（无 agent 作用域）。若为每个 agent 克隆 Toolkit，需重构全部构建点并管理每实例状态。
2. **注册时机与生命周期**：MCP 服务器可能启动慢、掉线、配置中途变更（保存/启停/删除）。纯启动注册会让配置变更不生效；纯懒注册会让首轮对话缺工具。
3. **白名单与策略的执行层**：AgentScope 原生支持注册期过滤（`McpServerConfig.setEnableTools`），但 allow/ask/deny 访问策略是 QwenPaw 语义，AgentScope 的 `PermissionEngine` 与 majo 既有的 `ApprovalHook` 审批流是两套体系。

## Decision

**以"全局共享 Toolkit + 桥接服务"接入，四个子决策如下。**

### D1：全局共享 Toolkit，启动期注册（不克隆 per-agent Toolkit）

MCP 卡片是全局配置（与 QwenPaw 的全局 drivers 目录语义一致），agent 间共享 MCP 工具集是既有产品语义。新增 `McpToolBridge`（Spring 服务）在启动时把全部**已启用**卡片注册进共享 `Toolkit` bean；6 个 agent 构建点零改动。若未来出现 agent 级 MCP 作用域需求，再以 ADR 评估 Toolkit 克隆。

### D2：生命周期 = 启动注册 + 卡片变更重注册

- 启动：`ApplicationRunner` 调 `bridge.registerAll()`。
- 变更：卡片保存 / 启停 / 删除时，桥接服务对受影响 client 执行 `Toolkit.removeMcpClient(key)` 后按新配置重注册（失败不回滚配置，只记录）。
- 注册结果（`McpServerRegistrationListener` 的 success/failed/skipped）写入日志并追加 inbox 事件（`mcp_registered` / `mcp_failed`），让前端可感知连接失败。

### D3：白名单在注册期由 harness 原生过滤

卡片 `config.tools` → `McpServerConfig.setEnableTools(...)`：白名单外的工具**不注册**进 Toolkit（对模型不可见），与 QwenPaw #7504 的"运行时路径强制"语义等价且更彻底。调用期兜底：卡片在两次调用间被停用时，已注册工具仍会存在——由 D4 的运行时检查拦截。

### D4：调用期治理挂在 ToolGuardHook（复用既有审批流）

`ToolGuardHook`（PreActing）新增 MCP 检查：`toolkit.getTool(name) instanceof McpTool` → `getClientName()` 定位卡片 → 读取访问策略：

- `deny`（或卡片已停用/已删除）→ 直接拒绝该次工具调用；
- `ask` → 复用 `ApprovalHook` 的既有审批流（`ApprovalStore.register` + await，前端 pending 列表无需改动）；
- `allow` → 放行，继续走既有的 Tool Guard / File Guard / 审批级别判定。

**备选（未采用）**：把 majo 的 `ApprovalStore` 适配进 AgentScope `PermissionEngine`——需要引入 PermissionContextState 会话语义并重写审批流，收益只是统一抽象，成本过高，留待与上游 Permission 体系对齐时再评估（见 Consequences）。

### D5：超时映射到 harness 客户端

卡片 `endpoint.http_timeout`（秒）→ `McpServerConfig.setTimeout(...)` 与 `setInitializationTimeout(...)`；未配置时用 harness 默认。凭据解析（headers/env 的 credential binding）复用 `McpBinding` 的既有解析，OAuth bearer 由 harness 客户端经 headers 注入静态值（OAuth 令牌刷新沿用既有 `McpOAuthService`，桥接只读其凭据快照）。

已知限制：harness 的 `McpServerConfig` 不支持 stdio 工作目录（`cwd`），stdio 服务器在宿主进程 CWD 启动——卡片的 `cwd` 字段在桥接路径暂不生效，需上游支持后再接入。

## Consequences

### 正面影响

- MCP 从"死配置"变为可用功能；白名单/策略/OAuth/超时四块既有投入全部盘活
- 6 个 agent 构建点（console/chat/channel/cron/root-compat/subagent）零改动——所有 agent 同时获得 MCP 工具
- 注册期过滤 + 调用期兜底双层防护，符合 QwenPaw 2.2.x 的运行时强制语义

### 负面权衡

- 全局注册意味着单个 MCP 服务器故障重试会影响共享 Toolkit（ harness 注册器已隔离失败，仅影响该 server 的工具集）
- `ask` 策略复用审批流：审批 UI 的工具名展示的是 MCP 原始工具名（无服务器前缀），多服务器同名工具时需结合 pending 记录的 agent/session 上下文辨认
- 与 AgentScope `PermissionEngine` 双轨并行，待上游权限体系成熟后可能需要一次收敛（预期由新 ADR 记录）

### 对已有规范的影响

- RFC 中 MCP 相关章节的"运行时桥接"缺口关闭
- `ToolGuardHook` 的事件路由扩展为：PRE_REASONING（媒体降级）→ PRE_ACTING（MCP 策略 → Tool Guard → File Guard → 审批）→ POST_ACTING（媒体提升）

## Implementation Notes

- `McpToolBridge`：`registerAll()` / `reRegister(clientKey)`；transport 映射 `stdio | streamable_http | sse`
- `McpService` 暴露卡片 → `McpServerConfig` 的装配（含凭据绑定与 enableTools）
- 卡片写入点（save/toggle/delete）调用 `reRegister`
- 测试：桥接装配（transport 映射/白名单/超时/停用卡片不注册）、hook 治理（deny 拒绝/allow 放行/非 MCP 工具不受影响）
