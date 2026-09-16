# ADR-0011：Token 用量按轮次持久化与 Agent 统计面

## Status

Accepted（2026-09-16）

## Context

QwenPaw 2.2.x 有完整的 token 治理面：`token_usage/`（manager / storage / turn_usage 三层）+ `agent_stats/`，能回答"这个 agent 这个会话花了多少 token、哪天花的、用哪个模型"。

majo 现状只有**日粒度聚合**：`token_usage` 表按 (日期, provider, model) 累加 prompt/completion tokens 与 call_count（`ConsoleController` 在流结束时 `save(new TokenUsageEntity(today, ...))`），`GET /token-usage` 输出 by_model / by_date 汇总。缺失的维度：

- **没有 agent 维度**——多 agent 场景下无法区分谁花的；实体无 agent_id/session_id 列。
- **没有轮次粒度**——单轮 input/output 与耗时没有留痕，无法回看某次会话的成本构成，QwenPaw 的 turn_usage 语义缺失。
- 前端 Chat 的 `useTurnUsageStore` 已从 SSE（`turn_usage` 事件）拿到**当前轮**用量，但只存在于内存 store，刷新即失。

## Decision

**新增按轮次记录表 `token_usage_turns` + agent 维度统计端点；既有日聚合表与 API 契约保持不变。**

### 数据模型

- `V25__create_token_usage_turns.sql`：`token_usage_turns(id, agent_id, chat_id, provider_id, model, input_tokens, output_tokens, duration_ms, created_at)`。
- `TurnUsageEntity` + `TurnUsageRepository`（按 agent_id + created_at 范围查询）。
- 日聚合表 `token_usage` 原样保留——它服务既有 `/token-usage` 端点，前端已在消费，契约零破坏。

### 记录点（对齐 QwenPaw turn_usage 语义）

- `TokenUsageService.record(...)`：单点写入，异常只告警不阻断主流程。
- Console 单轮与 loop turn 的 `doFinally`（已有 `usageHolder` 捕获 ModelCallEndEvent）在保存日聚合的同一处补写 turn 记录（agent_id + chat_id + duration）。

### 统计端点

- `GET /api/token-usage/agents?start&end`：按 agent 汇总（input/output/calls/turns），与既有 `/token-usage` 同风格。`get_token_usage` 工具同步暴露 agent 维度。

### 明确不做

- 不做 per-request 计费/预算熔断（QwenPaw 的 budget gate 已由 `TokenBudgetGate` 在 loop 维度承担）
- 不迁移既有日聚合数据（两表并存，粒度不同、用途不同）

## Consequences

- 多 agent 部署的成本归因成为可能；会话成本可回看
- 两个保存点各多一次 insert（H2 本地库，开销可忽略）；turn 表增长按需清理（无自动 TTL，后续需要时加）
- 前端 turn usage store 未来可从该表恢复历史，本 ADR 不含 UI 改动
