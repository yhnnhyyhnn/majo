# QwenPaw #7899 供应商统一——majo 能力差距盘点

> 调研文档(2026-10-13)。对应上游提交 d7afd981 "feat(providers): unify
> model discovery, pricing, selection and thinking controls (#7899)"
> (265 文件)。目的:为"是否/如何立项移植"提供依据,本文档本身不改代码。
> 结论先行:**majo 的问题不是缺管理面,而是执行层断裂**——配置与 UI 三层
> 齐备,但思维控制、供应商协议差异、价格/成本三块没有一条接到真实请求
> 上。#7899 恰好补的就是这三块的执行语义 + 社区目录数据。整批移植不现
> 实(其 Hub/SDK 深度耦合),但按块拆解后有 4 个可独立立项的候选。

## 一、上游 #7899 交付了什么

| 块 | 上游实现 | 关键文件(src/qwenpaw/) |
|---|---|---|
| 统一模型目录 | models.dev 社区目录快照:index.json(6.7k 行)+ 24 个 per-provider JSON(能力/上下文/价格/推荐标记,`probe_source: documentation`)+ 版本化排名证据(Artificial Analysis 智能指数,作为自动选型门槛) | `providers/data/*`、`model_info.py`、`model_catalog.py` |
| 定价 | "保守分类":显式数值价格才保留,免费需完整零费率或显式标记,绝不补零造数 | `model_billing.py` |
| 统一解析 | token 能力(上下文窗口/输出上限)一次解析供 UI 与运行时共用;自动值永不固化为用户覆盖 | `model_resolution.py` |
| 会话级思维控制 | 用户意图(ThinkingPreference: off/minimal..max/budget)与各模型的已验证控制面(ThinkingControl: efforts/预算范围/`wire` 风格)分离;会话级持久且不改 agent 默认 | `thinking.py`、`services/session_thinking.py` |
| 原生协议适配 | 把统一 Chat 契约翻译为原生协议:Anthropic messages(含 thinking 参数、max_tokens 策略)、Gemini 预算、OpenAI chat/responses;缓存策略与 usage 抽取适配器 | `adapters/wire_protocol.py`(414 行)、`adapters/anthropic.py`、`adapters/cache_policy.py`、`adapters/usage.py` |
| 会话级选模型 | 会话内临时切模型/思维档(pendingSessionSettings,落会话不落 agent) | console `features/session-settings/*` |

## 二、majo 现状(2026-10-13 盘点)

管理面已相当完整:33 供应商/175 模型种子(`config/providers.json`),
发现(`POST /models/{id}/discover` → OpenAI 兼容 /models)、连接测试、
OpenRouter OAuth、思维配置 UI(RemoteModelManageModal 开关/滑条/档
位)、用量记录(日表+轮表,ADR-0011)。断点全部在执行层:

1. **思维控制是死配置**。`ProviderModelEntity` 的 thinking_enabled/
   thinking_budget/reasoning_effort/thinking_param_style 与供应商级
   generate_kwargs 均可存可改可展示,但 `ModelRoutingService.
   buildOpenAIChatModel`(ModelRoutingService.java:175-197)只设
   apiKey/baseUrl/modelName/contextWindowSize 四项;全后端 grep
   `enable_thinking` 零命中。
2. **协议适配不存在**。providers.json 声明了 5 种 chat_model 类型
   (OpenAI×27/DashScope/OpenAIResponse/Anthropic×3/Gemini),Java 后端
   只有 `OpenAIChatModel` 一个实现,声明被 build 时忽略。Anthropic 直连
   的 thinking 参数、Gemini 思考预算、Response API reasoning items 均
   无差异处理。供应商级 custom_headers 同样只存不用。
3. **无价格、无成本**。模型目录无价格字段,token 用量两张表只算
   token 数;上游 #8090 那类"新模型参数识别"也因此无落点(请求体由
   SDK jar 组装)。
4. **无会话级覆盖**。选模型最细粒度是 agent(scope 仅 `global|agent`),
   会话内临时切模型/切思维档不存在。
5. **多模态探测是字符串启发式**(vision|vl|omni|gpt-4o|gemini),不发
   真实请求;真实元数据只有种子静态标注与 OpenRouter 实时 API。
6. **无社区目录同步**。种子是 0.4.0 时点手工快照,模型迭代靠发版。

## 三、差距矩阵(#7899 各块 vs majo)

| #7899 能力块 | majo 现状 | 差距性质 |
|---|---|---|
| models.dev 目录数据+排名 | 静态种子,无排名 | **数据缺口**(易补:目录是纯 JSON) |
| 定价+成本折算 | 无价格字段,用量无成本 | **数据+展示缺口**(用量表已在,只缺价格源) |
| 统一 token 能力解析 | 已区分窗口/输出上限(getContextSize) | 基本对齐,小差距(自动值/用户覆盖边界) |
| 会话级思维控制 | 配置层全有,**执行断裂** | **执行缺口**(最大单点价值) |
| 原生协议适配(Anthropic/Gemini) | 单一 OpenAI 兼容通道 | **执行缺口**(依赖 SDK 是否可扩展) |
| 会话级选模型 | agent 级为最细粒度 | 中等(UI+解析链扩展) |

## 四、立项建议(按价值/风险排序,均需独立 ADR)

1. **思维控制接通执行层**(候选 ADR-0016):把 majo 已有的
   thinking_param_style(budget/effort)+budget/effort 值接进
   `buildOpenAIChatModel` 的请求参数。**可行性已验证**(2026-10-13
   javap 本地 agentscope-core 2.0.0 jar):`OpenAIChatModel.Builder`
   暴露 `generateOptions(GenerateOptions)`,而 `GenerateOptions` 原生
   支持 `thinkingBudget(Integer)`、`reasoningEffort(String)`、
   `additionalBodyParams(Map)`(DashScope 的 enable_thinking 走这个)、
   `additionalHeaders(Map)`(顺带激活已存未用的供应商 custom_headers)。
   另注:GenerateOptions 同时有 maxTokens/maxCompletionTokens 两个槽,
   请求体参数选择在 SDK 内部处理——印证 10-13 扫描对 #8090"无落点"
   的判断。**推荐首批,纯接线工作**。
2. **价格元数据+成本折算**(候选 ADR-0017):从 OpenRouter 实时 API
   (已在用)或 models.dev 快照引入价格,provider_models 加价格列(可
   null),用量汇总折算成本。与上游 `model_billing` 的"保守分类"原则
   对齐:无价格即不显示成本,不造数。
3. **models.dev 目录同步**:上游 data/ 目录的 JSON 结构可直接复用为
   majo 的目录更新源(24 个 per-provider 文件,含能力/上下文/价格/推
   荐)。替换手工种子维护;排名证据暂不需要(majo 无自动选型)。
4. **Anthropic/Gemini 原生适配**:价值真实(Anthropic 直连的 thinking
   与缓存、Gemini 预算)但依赖 agentscope SDK 的多协议模型实现,需先
   验证 SDK jar 是否提供 AnthropicChatModel/GeminiChatModel 或可注入
   自定义 HTTP 层(extensions jar 里只有 OpenAI 系;或经
   `httpTransport`/`formatter` 钩子自实现);providers.json 的
   chat_model 声明已在等待它。风险最高,放最后。

**不建议**整批移植 #7899:其 model_service/Hub 网关、runtime_policy、
console 六语言 i18n 与 vendor SDK 深度耦合,且 majo 的管理面已经齐备,
缺的是"最后一公里"。按上述 1→4 分批,每批可独立验证、独立回滚。

## 五、后续动作

- 每批立项时先写 ADR(08-ADR 目录,当前至 ADR-0015);
- 第 1 批(思维控制接线)的 SDK 通路已验证(见第四节),可直接进入
  ADR-0016 编写与实现;
- 本文档结论已回链 `qwenpaw-sync-log.md` 9/22 条目的 #7899 暂缓项。
