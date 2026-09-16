# ADR-0014：记忆 LLM 提取后端——把自动记忆从"原文摘录"升级为"摘要落盘"

## Status

Accepted（2026-09-16）

## Context

ADR-0009 的写入管道把累积轮次的**原文摘录**交给 `MemoryBackend.remember()`——这是当时 keyword 后端（零 LLM 依赖）下的正确选择，但记忆质量有明显天花板：对话原文里大量的过程性内容（工具输出回显、中间推理、寒暄）与真正值得长期记住的事实（用户偏好、项目决策、环境事实）混在一起落盘，检索命中率被稀释。QwenPaw 的对应层是 ReMe 的 `auto_memory` job——LLM 提取后再落 markdown。

ADR-0008 的 SPI 让这件事不需要动管道：换后端即可。

## Decision

**新增 `summary` 记忆后端（`SummaryMemoryBackend`），在 remember() 前经 `MemorySummarizer` 做一次 LLM 提取；提取失败或判空时回退原文摘录，绝不丢轮次。**

### 1. `MemorySummarizer` SPI + `LlmMemorySummarizer`

- `Optional<String> summarize(String agentId, String content)`：返回"值得长期记住的事实"清单（几行以内）；空 = 这批轮次没有沉淀价值。
- LLM 实现：`ModelRoutingService.resolveEffectiveModel(agentId)` 构建与 agent 相同的模型，`model.stream(...)` 单次调用、固定提取 prompt（角色：记忆策展人；只输出事实条目、没有则输出"无"）、30s 超时、异常向上抛由后端兜底。
- **不引入新配置**：summary 后端的开销就是所选模型的一次小调用；后端选择沿用 `memory_manager_backend="summary"`。

### 2. `SummaryMemoryBackend extends KeywordMemoryBackend`

- `id()="summary"`；检索/索引/生命周期全部继承 keyword（同一 memory/ 目录与倒排索引）。
- `remember()`：summarize → 有摘要落摘要（metadata 标 `extraction=llm_summary`），异常/判空落原文（`extraction=raw_excerpt`）——**LLM 不可用时行为退化为 ADR-0009 的 keyword 后端，而不是失效**。
- 注册表自动收录（Spring bean），`resolve` 回退序里排在 keyword 之后。

### 3. 顺带修正（本 ADR 范围内的两个既有缺陷）

- **remember() 多 agent 串写**：KeywordMemoryBackend.remember 原本把同一条记忆写进**所有**已 start 的工作区——单 agent 下无症状，多 agent 下 A 的记忆会落进 B 的目录。改为 metadata 带 `agent_id` 时只写对应工作区；`MemoryWritePipeline` flush 时补发该字段。
- **索引缓存双键不一致**（CI Docker 门禁失败根因）：索引缓存按 agentId 键控而 rebuildForPath 按工作区目录名推导键，两者分叉时 search 会读到陈旧索引（CI 上 `forget` 后仍搜得到已删记忆）。缓存键统一为工作区绝对路径。

### 明确不做

- 不做增量摘要/记忆去重/遗忘曲线——后端内部优化，另行演进
- 不做向量/embedding——ADR-0008 已划归具体后端实现

## Consequences

- `memory_manager_backend="summary"` 一键升级记忆质量，管道/命令面/注册表零改动
- 每 flush 一次额外小模型调用（默认 auto_memory 关闭，启用者自担成本）
- LLM 故障时记忆质量静默降级为原文摘录（metadata 里 `extraction` 字段可观测）
