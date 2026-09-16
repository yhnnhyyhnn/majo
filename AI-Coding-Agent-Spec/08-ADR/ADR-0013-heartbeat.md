# ADR-0013：Heartbeat 心跳——HEARTBEAT.md 定时驱动 agent 运行

## Status

Accepted（2026-09-16）

## Context

QwenPaw 有两套同名 heartbeat，其中**定时任务心跳**（`app/crons/heartbeat.py`）按配置周期把 `HEARTBEAT.md` 文本作为一条 user 消息让 agent 跑一轮，结果按 target 投递（`main`=只跑不外发 / `inbox`=写收件箱 / `last`=转发到用户上次联系的渠道）。它是"一个特殊 cron job"（id `_heartbeat`），带 active_hours 窗口与单次超时。

majo 现状是又一个"死配置"（与 ADR-0007/0010 修复的同病）：

- V13 迁移已把 `heartbeat_enabled/every/target/timeout_seconds` 落在 settings 表，`GET/PUT /config/heartbeat` 读写正常，前端可以开关——
- 但**没有任何调度器消费这些配置**；`POST /config/heartbeat/run` 是返回 `{"started": true}` 的空壳。
- majo 的 cron 体系（`CronManager` 按 agent 懒创建 + `CronExecutor`）与心跳的语义差异：cron 是用户声明的任务清单（磁盘 JSON），心跳是单一全局行为（settings 表驱动）。二者不该硬套。

## Decision

**新增全局 `HeartbeatScheduler`（`cron/` 包），消费既有 settings 配置，周期性用 HEARTBEAT.md 驱动默认 agent 运行。**

- **调度**：`every` 解析对齐 QwenPaw——合法 5 字段 cron 表达式 → `CronTrigger`；否则按间隔串解析（`90s`/`30m`/`2h30m`，默认 `6h`）→ 固定间隔。解析失败回退默认值并告警，配置变更（PUT）即重排。
- **单次运行**：读默认 agent 工作区的 `HEARTBEAT.md`，缺失或空 → 跳过（安静）；否则以其全文为 user 消息构建 `HarnessAgent` 运行（复用 CronExecutor 的构建模式），受 `timeout_seconds`（钳制 1..3600）限时，会话注册为 `heartbeat:main` 使前端会话列表可见。
- **投递 target**：`main`（默认）只运行不外发，失败/超时写 inbox 事件；`inbox` 成功也写结果预览事件（截 4000 字符，对齐 QwenPaw reme_inbox 的正文上限语义）；`last` 在 majo 无"最后联系渠道"存储，**回退为 inbox 行为**（ADR 明示，未来引入 last-contact 存储再补）。
- **手动触发**：`POST /config/heartbeat/run` 从空壳变为真实执行（异步，立即返回 started + 上一轮状态）。
- 不做 active_hours 窗口（QwenPaw 有）——majo 的 settings 模型暂无该列，避免再造半套配置；需要时随列迁移一起补。

## Consequences

- 心跳从"可配置但无效"变为真实能力：定时自检、周期汇报、`inbox` 模式下的无人值守产出
- 每 tick 一次 LLM 调用——默认 6h + 默认关闭，成本可控；HEARTBEAT.md 缺失时零开销
- `last` 目标暂回退 inbox，是有记录的功能缺口而非静默偏差
