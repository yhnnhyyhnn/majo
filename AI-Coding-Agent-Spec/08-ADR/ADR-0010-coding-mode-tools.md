# ADR-0010：Coding Mode 与代码智能工具——对齐 QwenPaw 的模式化编码能力

## Status

Accepted（2026-09-16）

## Context

QwenPaw 2.2.x 的编码能力是**模式化**的：`AgentMode` 捆绑 commands/tools/hooks/promptContributors 四类贡献物，coding 模式由 agent 配置 `coding_mode.enabled` 驱动（非 slash 命令），激活后注入编码纪律 system prompt（TODO 清单追踪、`path:line` 引用格式、工具偏好），并按**能力探测**条件注册 `lsp`（语言服务器 JSON-RPC 客户端）与 `ast_search`（shell 出 ast-grep CLI，只读）两个工具——探测不到语言服务器/二进制就不注册，错误一律以模型可读文本返回。

majo 现状：

- 无模式概念对 prompt/工具的影响；`SYS_PROMPT` 是硬编码的"编码助手"文本。
- 代码智能只有正则级的 `search_code`/`find_symbol`（find_symbol 仅匹配 `class|interface|enum|record` 声明行），无真正的定义跳转/引用查找/结构化模式匹配。
- majo 的 Toolkit 是**全局共享单例**（`AgentConfig.toolkit`），所有 agent 复用；QwenPaw 的"按活跃模式过滤工具注册表"模型没有直接对应物。
- majo 已有自己的 loop 模式体系（goal/mission/custom，`LoopCompiler`），与 QwenPaw 的 modes 在"循环终止门"维度同构——本 ADR 不重复建设该维度。

## Decision

**引入配置驱动的 Coding Mode + 两个能力探测型代码智能工具，prompt 走运行时注入、工具走调用时门控。**

### 1. Coding Mode（`agent/CodingModeService`）

- 状态源沿用**既有的** agent profile `coding_mode.enabled`（侧栏 CodingModeToggle 与 `GET/POST /api/coding-mode` 已存在，默认关）——本 ADR 补齐的是它缺失的**运行时联动**：此前开关只是前端状态，prompt 与工具行为从未随之变化（与 ADR-0007 修复的"死配置"同病）。
- 启用后注入 coding 纪律 prompt 并放行 `lsp`/`ast_search`；关闭时两工具返回可读的"未启用"提示。agent 为每请求构建（`resolveAgent`），配置变更自然在下一条消息生效，无需 QwenPaw 的 `schedule_agent_reload`。
- 与 QwenPaw 的偏差（有意为之）：QwenPaw 在工具注册表按 `requires_modes` 过滤、模式不活跃则工具不存在；majo 的 Toolkit 全局共享，改为**调用时门控**。运行时效果等价（模型得到确定性行为），代价仅是工具 schema 常驻列表；这是共享 Toolkit 架构下最小的对齐成本。

### 2. Coding 纪律 prompt（`agent/CodingModePromptInjector`）

经 `ToolGuardHook` 的 PRE_CALL 分支追加到 system message（与 ADR-0009 的 AGENT.md 热注入同一挂接点、同样的"每次重读盘"语义），移植 QwenPaw `_CODING_SYSTEM_PROMPT_TEMPLATE` 的可移植要点：非平凡任务建 `{SLUG}_TODO.md` 勾选清单并即时翻勾 + 加入 `.gitignore`；代码引用一律项目相对 `path:line`（区间 `:42-58`）；"定义在哪/谁调用"优先 `lsp`，结构化模式查询优先 `ast_search`（只读，改写先读后 edit_file），都不行才 grep；先读后写、局部 edit_file 优先、只改任务范围。

### 3. `ast_search` 工具（`tool/AstSearchTool`）

- 语义对齐 QwenPaw：shell 出 PATH 上的 `ast-grep`/`sg` 二进制，`run --pattern P --lang L --json=compact [path]`，cwd=工作区；30s 超时；0-based 行列转 1-based；输出上限 80k 字符；无匹配/二进制缺失/非零退出都返回模型可读文本（含安装提示）。严格只读。
- 参数：pattern（必填）、language（必填）、path（可选，相对工作区）、max_matches（默认 200，钳 1..1000）。

### 4. `lsp` 工具（`tool/LspTool` + `tool/LspClient`）

- 六个子操作：goToDefinition / findReferences / hover / goToImplementation（需 file+1-based position）、documentSymbol（需 file）、workspaceSymbol（需 query）。**不做** diagnostics/增量同步（QwenPaw 客户端明确舍弃）。
- 语言服务器发现：PATH 探测 `typescript-language-server`（ts/tsx/js/jsx）、`pyright-langserver` → `pylsp`（python）；不可用语言在错误中明示并建议回退 grep/ast_search。
- 客户端：JSON-RPC over stdio（Content-Length 帧），按 `(工作区, 语言)` 常驻进程池；initialize(rootUri) → 每操作前 didOpen → 请求；每请求 15s / initialize 30s 超时；结果 JSON 序列化 80k 截断。
- 语言判定按文件扩展名；未知语言/无服务器 → 可读错误。

### 5. 明确不做（本 ADR 范围外）

- 不改 loop 门控的 mode scope 选择（majo 的 LoopCompiler 已覆盖 goal/mission/custom 维度）
- 不做 Heartbeat（HEARTBEAT.md 定时驱动）——独立机制，后续批次评估
- 不做 LSP 诊断推送/多根工作区——与 QwenPaw 客户端同样明确舍弃

## Consequences

### 正面影响

- majo 第一次拥有真正的代码智能（定义跳转/引用/结构化匹配），而非正则近似
- Coding Mode 开关让"编码助手"与"通用助手"两种形态可配置，prompt 与工具行为联动
- 两个工具都"探测可用才真正可用"，缺依赖时的错误路径可直接指导安装

### 负面权衡

- 调用时门控使工具 schema 常驻（关掉 coding mode 仍占上下文）——工具仅 2 个，开销可忽略
- lsp/ast_search 依赖外部二进制（语言服务器、ast-grep），未安装时功能缺失——与 QwenPaw 相同的部署语义，错误文本已给出补救路径
- LSP 子进程池按工作区常驻——每个 (workspace, language) 一个进程，与 QwenPaw 一致；majo 单机部署可承受

### 对已有规范的影响

- `find_symbol` 保留（零依赖兜底），prompt 引导优先 lsp；`AgentConfig.TOOL_NAME_MAP` 增加 lsp/ast_search
- ConsoleController/ChannelDispatcher 的静态 SYS_PROMPT 不动——lsp/ast_search 的可用性语义由注入的 coding prompt 承载
