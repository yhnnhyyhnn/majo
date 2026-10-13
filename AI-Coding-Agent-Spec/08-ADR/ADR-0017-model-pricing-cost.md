# ADR-0017: 模型价格元数据与成本折算

日期:2026-10-13 | 状态:已采纳 | 关联:ADR-0011 / [#7899 差距盘点](../07-Operation/qwenpaw-7899-provider-gap.md) 第 2 批 | 上游参照:QwenPaw #7899 model_billing(保守分类原则)

## 背景与问题

majo 的用量记录完整(ADR-0011:日表 token_usage + 轮表
token_usage_turns),但只算 token 数——模型目录没有价格字段,用量
汇总没有成本,用户无从知道一个 agent/一天花了多少钱(差距文档第二
节第 3 条)。上游 #7899 的 model_billing 给出关键原则:**保守分类**,
"显式数值价格才保留;免费需完整零费率或显式标记;绝不补零造数"。

## 决策

### 1. 价格存储

`provider_models` 加两个可空列:`input_price`/`output_price`,单位
**USD/百万 tokens**(models.dev 与 OpenRouter 的通行口径,避免浮点
过小)。null=未知。免费=两者显式 0(沿 isFree 语义,不造数)。
V28 迁移,存量行为 null。

### 2. 价格来源(两条,均不造数)

- **手动**:PUT /models/{pid}/models/{mid}/config 接受
  `input_price`/`output_price`(null 清除),ModelInfoDto 透出,
  RemoteModelManageModal 提供两个数字输入;
- **OpenRouter 导入**:POST /api/openrouter/import-pricing 拉实时
  models API,对 openrouter 供应商下 model_id 匹配的行 upsert 价格
  (per-token × 1e6),只写不删,解析失败的条目跳过。

### 3. 成本折算——记录时快照

新增 `ModelPricingService`:
- `pricesFor(providerId, modelId)` → (input, output),任一为 null
  即"未知";
- `costFor(providerId, modelId, in, out)` → in/1e6×inputPrice +
  out/1e6×outputPrice,**任一价格未知返回 null**(不半算)。

成本在**轮次落账时快照**写入两张表的 `cost_usd`(可空),价格此后
变更不影响历史账目(账本语义);ConsoleController 两处重复的
"日表+轮表"写入块收拢进 `TokenUsageService.recordTurnAndDaily`
(顺带消重)。

### 4. 汇总与展示

- /token-usage 汇总:byModel/byDate/summary 增加 `cost_usd`
  (仅累加非空值;无任何计价记录时为 null);
- /token-usage/details 行级透出 `cost_usd`;
- 前端:SummaryCards 加"总成本"卡,byModel 表加成本列,null 显示
  "——"(保守原则的 UI 面:未知不显示 0)。

## 备选与取舍

- **查询时 join 现价计算**:零表结构变更于用量表,但价格修改会改写
  历史账目,且日表无外键关系可 join 的时机成本高,放弃;
- **只做 OpenRouter 自动导入不做手动**:非 OpenRouter 供应商(多数
  国内直连)无价格来源,手动是唯一通道,必须共存;
- **models.dev 快照做种子**:第 3 批(目录同步)的范畴,届时批量灌
  价格,本批先立好字段与折算管道。

## 后果

- 正面:成本可见;价格快照使账目稳定;两条来源互补;保守原则保证
  未知绝不显示为 0。
- 负面/限制:价格需手动维护(第 3 批落地前);缓存/批处理折扣价
  (OpenRouter prompt_cache_hit 等)未建模,按牌价估算是下界。
- 兼容:新列全可空,未配置价格时 API 多出的 cost 字段为 null,旧
  前端忽略未知字段不受影响。

## 验证

ModelPricingServiceTest(计价算术/单边未知返回 null/免费为 0/
非数字容错)、TokenUsageService 计价落账与容错(落账失败不阻断
回合)、OpenRouter 导入的匹配与跳过逻辑。
