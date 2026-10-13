# ADR-0016: 思维控制接通执行层(GenerateOptions 接线)

日期:2026-10-13 | 状态:已采纳 | 关联:ADR-0006 / [#7899 差距盘点](../07-Operation/qwenpaw-7899-provider-gap.md) 第 1 批 | 上游参照:QwenPaw #7899(thinking/compat 线上翻译)

## 背景与问题

majo 的思维控制配置三层齐备——`ProviderModelEntity` 的
thinking_enabled/thinking_budget/reasoning_effort/thinking_param_style、
供应商级 generate_kwargs/custom_headers、RemoteModelManageModal 的开关/
滑条/档位 UI——但全部是**死配置**:`ModelRoutingService.
buildOpenAIChatModel` 只设 apiKey/baseUrl/modelName/contextWindowSize
四项,用户在 UI 上调的任何思维参数都不会进入请求(2026-10-13 盘点,
差距文档第二节第 1 条)。

上游 #7899 的核心正是"用户意图→已验证控制面→线上参数"的执行语义;
其兼容层翻译(openai_chat_model_compat._consume_disable_thinking、
dashscope_provider._apply_thinking_config)给出 OpenAI 兼容通道的
既定口径,本 ADR 按该口径做最小接线,不引入上游的
ThinkingPreference/ThinkingControl 双模型。

## 已验证的 SDK 通路(agentscope 2.0.0,javap 反汇编确认)

`OpenAIChatModel.Builder.generateOptions(GenerateOptions)` 配置的选项
随每个请求生效:

- `GenerateOptions.thinkingBudget` → 请求体顶层 `thinking_budget`
  (OpenAIChatFormatter.applyOptions → OpenAIRequest.setThinkingBudget);
- `GenerateOptions.reasoningEffort` → 请求体顶层 `reasoning_effort`;
- `GenerateOptions.additionalBodyParams` → OpenAIRequest.extraParams,
  经 `@JsonAnyGetter` **摊平为请求体顶层字段**;
- `GenerateOptions.additionalHeaders` → OpenAIClient.buildHeaders 在
  Authorization/Content-Type/User-Agent 之后 putAll 进 HTTP 头;
- maxTokens/maxCompletionTokens 的 GPT-5 区分在 SDK 内部(applyMaxTokens),
  majo 不涉及(印证 #8090 无落点的判断)。

## 决策

### 1. 接线点与配置来源

`buildOpenAIChatModel(providerId, modelId)` 保持唯一接线点(全部
agent 构建路径经此)。为内置供应商追加读取 ProviderEntity(级配置)
与 ProviderModelEntity(模型级配置);自定义供应商(model_configs)
无思维配置,行为不变。

### 2. 线上翻译(对齐上游兼容层口径)

**风格解析**:`model.thinkingParamStyle`,空则回落
`provider.thinkingParamStyle`,仍空 → **不发任何思维参数**(不猜)。

| 风格 | thinkingEnabled | 线上参数 |
|---|---|---|
| budget | true | body `enable_thinking: true`;budget>0 时 `thinking_budget: n`(走 GenerateOptions.thinkingBudget) |
| budget | false | body `enable_thinking: false` + `thinking: {"type":"disabled"}`(上游对 Qwen/DeepSeek 双系的双保险翻译) |
| effort | true + effort 非空 | `reasoning_effort: <effort>`(走 GenerateOptions.reasoningEffort) |
| effort | false | **不发**(effort 系多为 OpenAI/DeepSeek 严格端点,未知参数会 400;"不选"即不启用,保守) |
| 任意 | null | 不发 |

### 3. generate_kwargs(专家逃生口)

供应商级为基、模型级覆盖(shallow merge);兼容上游历史形态:若 JSON
含 `extra_body` 嵌套对象则**提升为顶层**再合并(上游 extra_body
promotion 语义)。经 additionalBodyParams 摊平进请求体。思维字段在
kwargs 合并**之后**应用(UI 管理的配置压过手写 kwargs 的同名键)。

### 4. custom_headers

供应商级 custom_headers(JSON 对象)→ additionalHeaders。至此该字段
从"只存不用"变为生效。

### 5. 失败语义

generateKwargs/customHeaders JSON 解析失败 → warn 日志 + 忽略该配置
(fail open,与 ModelRequestNormalizerHook 同原则);思维字段非法
(如 budget≤0)→ 跳过该项。**任何配置错误都不阻断模型构建**。

## 备选与取舍

- **整批移植上游 ThinkingPreference/Control**:双模型+会话级服务是
  #7899 全套依赖(会话设置存储/UI 控件),超出第 1 批范围;会话级
  覆盖留待第 2 批(见差距文档)。
- **effort 禁用也发双保险**:OpenAI 系端点对未知顶层参数直接 400,
  风险大于收益,放弃。
- **maxTokens(输出上限)同时接线**:实体字段语义是元数据展示,
  强行 cap 响应长度是行为变更,超出本批;留给需要时单独立项。

## 后果

- 正面:UI 上的思维开关/预算/档位首次真实生效;供应商 custom_headers
  与 generate_kwargs 激活;纯接线零新表零迁移。
- 负面/限制:配置立即影响所有走该模型的请求(这正是目的);generate
  _kwargs 顶层键可能与 DTO 显式字段重名(Jackson 双键,服务端取后者,
  用户显式配置自负);风格判断依赖种子数据的 thinking_param_style
  标注质量。
- 兼容:无配置(全部为空)时请求体与之前完全一致——不新增键。

## 验证

ModelRoutingServiceThinkingTest(7 用例,Mockito 假仓储):budget
启用双参数、budget 禁用双保险、effort 档位、无风格零污染、kwargs
双层合并与 extra_body 提升、customHeaders 进 HTTP 头、坏 JSON 不阻断
构建。
