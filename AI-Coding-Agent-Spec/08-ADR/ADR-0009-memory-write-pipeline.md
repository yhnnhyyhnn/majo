# ADR-0009：记忆写入管道与 /memory 命令——补齐 ADR-0008 遗留的写侧与治理面

## Status

Accepted（2026-09-16）

## Context

ADR-0008 落地了记忆后端 SPI（`MemoryBackend` + `MemoryBackendRegistry` + `KeywordMemoryBackend`），读侧（`memory_search` 工具、自动检索注入）已进入 agent 能力面，但**写侧仍然是空转的**：

- `KeywordMemoryBackend.remember()` 是 no-op（"read-only backend"）——SPI 契约里的写入方法没有任何真实调用点，记忆只能靠用户/agent 手工往 `memory/` 目录放 markdown。
- ADR-0008 "明确不做"清单中的两项正是本 ADR 主题：auto_memory 写入管道、记忆斜杠命令（QwenPaw `1174c6d9` 统一 ReMe 命令）。
- QwenPaw 2.2.x 的完整写侧闭环：`MemoryMiddleware`（`middlewares.py`）在轮次结束（on_reply）/压缩（on_compress_context）时累积 pending 轮次并按间隔提交 auto_memory job（ReMe 用 LLM 提取后落 markdown）；job 结果经 `reme_inbox.py` 以 inbox 事件通知用户；后端不可用时 slash 命令返回 `**Memory Manager Disabled**`（`2b09de79`）等可见降级提示。
- 另外：工作区的 `AGENTS.md`/`SOUL.md`/`PROFILE.md` 模板在 agent 创建时复制（`SkillRegistry.copyWorkspaceMdTemplatesForLanguage`），契约文本（`ProtectedPrompt`）也声称"这些文件的指令适用"，但**没有任何代码把它们读进 system prompt**——QwenPaw 的 PromptBuilder（`agents/prompt.py`）每次调用都热加载这三个文件 + `getMemoryPrompt()`，majo 缺失这最后一环。

## Decision

**在 `memory` 包内补齐写侧与治理面，全部复用 ADR-0008 的 SPI 与既有 hook/inbox 基础设施：**

### 1. 记忆写入管道（`MemoryWritePipeline`）

QwenPaw `MemoryMiddleware` 的 Java 对应物，以 Spring bean 实现、经 `ToolGuardHook` 复合挂接点路由 `PreCallEvent`/`PostCallEvent`（majo 所有 agent 构建点只注册这一个复合 hook——与 ADR-0007 的接线方式同构，不动 6 个构建点）：

- **PRE_CALL**：捕获本轮最新用户消息文本（控制命令 `/` 开头跳过），存入 pending 轮次。
- **POST_CALL**：追加本轮 assistant 最终回复，pending 计数达到配置间隔（`reme_light_memory_config.auto_memory_config.interval`，默认关闭 `enabled=false`）即 flush：把累积轮次交给 `registry.resolve(agentId).remember(content, metadata{trigger, turns})`，成功后清空 pending。
- **状态持久化**：pending 轮次落 `memory/.auto_memory_state.json`（对齐 QwenPaw 把轮次状态存 AgentState 的语义——进程重启/压缩不丢轮次）。
- **无 LLM 提取**：keyword 后端做不了 ReMe 的 LLM 摘要，写入的是轮次原文的限界摘录（每轮截断），以每日 markdown 追加的方式落 `memory/daily/`。换 LLM 后端（未来 ReMe SPI 实现）时管道不变，只换 `remember()` 的实现。

### 2. `KeywordMemoryBackend.remember()` 真实化 + `getMemoryPrompt()`

- `remember(content, metadata)`：追加写入 `memory/daily/YYYY-MM-DD.md`（时间戳小节 + metadata 注释），内容限界 8000 字符，写后增量重建索引。
- `getMemoryPrompt()`：返回简短记忆指引（何时调用 `memory_search`），QwenPaw `get_memory_prompt()` 语义。

### 3. `/memory` 斜杠命令（`MemoryCommandService`）

对齐 QwenPaw `1174c6d9` 的"统一记忆命令"但取 majo 的最小有用集：

- 子命令：无参/`status`（后端 id、可用性、索引文件数、配置开关）、`list [n]`、`search <query>`、`read <path>`、`forget <path>`（删除 md + 重建索引）、`write <text>`（显式 remember）、`help`。
- 注册进 `CommandRegistry`（level 10），新增 `POST /api/commands/run` 执行端点——console 前端与渠道消息都能走同一条命令面。
- 后端不可用（`resolve()` 返回 null）时返回可执行的修复指引文案（QwenPaw `**Memory Manager Disabled**` 语义），不是静默失败。

### 4. 用户可见降级（QwenPaw #7663 对应）

- 写入管道 flush 失败 / 后端不可用时发 inbox 事件（`source_type="memory"`、`event_type=auto_memory_result`、error/warning），对"后端恢复"去重——不可用告警每 agent 只发一次，恢复后重置。
- `/memory status` 与 `memory_search` 工具同样返回可用性状态。

### 5. AGENT.md 热注入（QwenPaw PromptBuilder 对应物）

- `MemoryPromptInjector`（并入 `MemoryWritePipeline` 的 PRE_CALL 分支）：每次模型调用前，把工作区启用的 system prompt 文件（`AGENTS.md`/`SOUL.md`/`PROFILE.md`，按 `/workspace/system-prompt-files` 配置）+ `backend.getMemoryPrompt()` 追加到 system message（`HookEvent.appendSystemContent`），每次重读盘——热生效，与 QwenPaw 一致。

## Consequences

### 正面影响

- 记忆闭环：自动写入 → 索引 → 检索（工具 + 自动注入）→ 命令治理，与 QwenPaw 写侧语义同构
- AGENTS.md/SOUL.md/PROFILE.md 从"复制了但没人读"变为真实生效的运行时输入
- 全部走既有 hook 挂接点与 inbox 面板，前端零新概念（inbox 事件自动出现）

### 负面权衡

- keyword 后端的"写入"是原文摘录而非语义提炼——记忆质量受限于摘录策略；SPI 保证未来换 LLM 提取后端时管道与命令面零改动
- PRE_CALL 每轮重读最多 3 个 md 文件——工作区文件量级下开销可忽略，与 QwenPaw 的无缓存热加载一致

### 对已有规范的影响

- ADR-0008 "明确不做"清单中两项由本 ADR 关闭；其余（向量后端）保持开放
- `CommandRegistry` 新增 `/memory`；`CommandsController` 新增 `/api/commands/run`
