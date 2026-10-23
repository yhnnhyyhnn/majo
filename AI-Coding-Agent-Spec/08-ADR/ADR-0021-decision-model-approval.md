# ADR-0021: 决策模型工具审批(MODEL 级)

日期:2026-10-15 | 状态:已采纳 | 关联:ADR-0007(工具门)/ ADR-0019 系列供应商接线 | 上游参照:QwenPaw #8283/#8205/#8360 decision_model(精神移植,非整搬)

## 背景与问题

majo 的工具审批级别(OFF/STRICT/SMART/AUTO)中,AUTO 只拦高危工具,
拦下后要么等人、要么拒绝——没有"自动但有判断"的中间态。上游 #8283
引入决策模型审批:由一个 LLM 对高风险工具调用做风险判定,自动批准
或拒绝。其实现(488 行 decision_model.py)绑定 AgentScope Python
Classifier、TypeSafe provider 与凭证扫描体系,不可整搬;majo 按自身
架构做精神移植。

## 决策

### 1. 级别与作用域

approval_level 新增 **MODEL** 级,作用于与 AUTO 相同的 GUARDED_TOOLS
集合(execute/write/git 提交等)。SMART/STRICT 语义不变。

### 2. 判定管道(两段,均 fail-closed)

1. **确定性硬拒(不问模型)**:工具输入(序列化 JSON)命中敏感凭证
   字面量(sk- 前缀 key / GitHub/AWS token 形态)或敏感路径(.ssh/
   .aws/.env/.kube/gnupg/history)→ 直接拒绝并注明命中类。上游同款
   思路:密钥永远不该出现在工具参数里,不需要 LLM 裁量。
2. **LLM 二分判定**:取该 agent 生效模型(既定路由链),以紧凑中文
   判定提示词(工具名+参数 JSON+明确的输出契约)发一次非流式
   chat/completions(30s 超时,小 max_tokens);解析 APPROVE/DENY
   (兼容"批准/拒绝"),歧义/超时/任何异常 → **拒绝**。

### 3. 模型选择与安全边界

- 判定用 agent 自己的生效模型(ADR-0019 管道解析连接)。独立决策
  模型配置(上游 #8205)留作后续:当需要"非当事人模型"时再加
  provider/model 覆盖,不阻塞本批;
- MODEL 级仅在轮内同步判定,不注册人工审批卡、不进 Inbox;
- 失败语义:判定不可得即拒绝(fail-closed)——审批上下文里静默
  放行的代价远高于误拒。

### 4. 前端

Agent Config 的 ToolExecutionLevelCard 与 Chat 页 ApprovalLevelToggle
增加 MODEL 选项(两处 ToolExecutionLevel union + LEVELS + 标签),
i18n 全 7 语言;normalizeLevel 同步放行。

## 备选与取舍

- **整搬上游 decision_model**:绑定 Python Classifier/TypeSafe,不可行;
- **独立决策模型配置**:治理上更优(非当事人裁决),但引入新配置面;
  首批用生效模型+文档化局限,覆盖配置留接口;
- **失败放行**:否——审批语境 fail-closed 是底线。

## 后果

- 正面:AUTO(全人审)与 OFF(全放)之间有了自动中间态;高危操作
  获得"带理由的自动裁决";确定性硬拒先行,敏感场景零 LLM 成本。
- 负面/限制:同一模型自裁存在自偏(文档化;独立决策模型为后续);
  每次判定一次额外 LLM 调用(高危工具频次低,可接受);提示词判定
  非安全边界,决定性硬拒仍先行。
- 兼容:不设 MODEL 级则行为与之前完全一致。

## 验证

DecisionModelGateTest(本地 HttpServer):APPROVE/DENY 解析(含中文)、
敏感字面量/路径不问模型即拒、超时与 HTTP 错误 fail-closed、模型解析
失败拒绝;ApprovalHookModelLevelTest:MODEL 级批准放行/拒绝重写
ToolUseBlock,级别不动时零行为变化。
