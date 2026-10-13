# ADR-0019: OpenAI 兼容端点适配(endpointPath 机制与 Gemini 修复)

日期:2026-10-13 | 状态:已采纳 | 关联:ADR-0016 / [#7899 差距盘点](../07-Operation/qwenpaw-7899-provider-gap.md) 第 4 批(降档实施) | 上游参照:QwenPaw #7899 adapters/wire_protocol

## 背景与问题

#7899 差距第 4 批原定"Anthropic/Gemini 原生协议适配"。2026-10-13
先探结论(反汇编 agentscope 聚合 jar + 反射实测 URL 拼接):

1. **SDK 有实现但不可用**:聚合 jar 含 AnthropicChatModel/GeminiChatModel
   (另有 dashscope/ollama),但 Anthropic 依赖官方 com.anthropic Java
   SDK、Gemini 依赖 com.google.genai + google-auth——均未打包、不在
   local-repo。原生路线 = vendor 重依赖,桌面包体积与不可端到端验证
   (无凭据)双风险,**挂起**。
2. **真实 bug 在兼容层**:SDK 的 URL 拼接(实测)为
   `base + endpoint(默认 /v1/chat/completions,带 /vN 去重)`:
   - anthropic 种子 `https://api.anthropic.com` → `/v1/chat/completions`
     = Anthropic 官方 OpenAI 兼容端点,**今天就能通**(Bearer 鉴权吻合);
   - **gemini 种子** `https://generativelanguage.googleapis.com` →
     `/v1/chat/completions` ≠ 官方兼容端点
     `/v1beta/openai/chat/completions`,**今天根本不通**;
   - `GenerateOptions.endpointPath` 可覆盖 endpoint(实测:
     base `.../v1beta/openai` + endpointPath `/chat/completions` →
     官方兼容端点)。

## 决策

### 1. endpointPath 机制

providers 加 `endpoint_path` 列(V29,可空);非空时经
`GenerateOptions.endpointPath` 覆盖默认 `/v1/chat/completions`。
接入点沿用 ADR-0016 的 GenerateOptions 管道(方法更名
resolveGenerateOptions)。DTO 透出、供应商配置端点可改。

### 2. Gemini 修复(存量 + 种子)

- V29 存量校正:仅当 gemini 行 base_url 仍为旧默认值(未被用户改过)
  时,改写为 `https://generativelanguage.googleapis.com/v1beta/openai` +
  `endpoint_path='/chat/completions'`;
- 种子 providers.json 同步新值,新装库直接正确。

### 3. 明确不做/待验证

- **原生 Anthropic/Gemini SDK**:挂起,先探结论记录在案(vendor
  com.anthropic / com.google.genai+auth 后方可启用;SDK Builder 亦有
  defaultOptions(GenerateOptions) 入口,届时 ADR-0016 管道可直通);
- **minimax(-cn)**:种子 base `.../anthropic` 走 OpenAI 拼接大概率
  404,但 MiniMax 的 OpenAI 兼容端点未经文档确认,不盲改,记录待验证;
- **兼容层的 thinking 参数**:anthropic/gemini 种子 thinking_param_style
  均为 null,ADR-0016 不发任何思维参数(无未知参数 400 风险);兼容层
  对思维参数的支持面未文档化,不主动发。

## 备选与取舍

- **vendor 官方 SDK 走原生**:thinking/缓存原生参数价值真实,但依赖
  体积与验证成本当下不划算,降档为兼容层修复;
- **改 SDK 默认 endpoint 常量**:改不了(jar 内),endpointPath 是官方
  预留的覆盖口,零侵入。

## 后果

- 正面:gemini 从"不通"变为官方兼容端点可用;endpointPath 成为所有
  OpenAI 兼容供应商的通用逃生口(自定义网关/代理路径);
- 负面/限制:兼容层无原生 thinking/缓存语义;gemini 修复只覆盖
  "未被用户改过"的存量行,手改过的需用户自行调整;
- 兼容:endpoint_path 为空时行为与之前完全一致。

## 验证

ModelRoutingServiceThinkingTest 增 endpointPath 用例(provider 配置
→ GenerateOptions.endpointPath);@SpringBootTest 全量跑 V29;种子值
经 ProviderSeedData 路径覆盖。
