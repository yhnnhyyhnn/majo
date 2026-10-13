# ADR-0018: models.dev 模型目录同步

日期:2026-10-13 | 状态:已采纳 | 关联:ADR-0016 / ADR-0017 / [#7899 差距盘点](../07-Operation/qwenpaw-7899-provider-gap.md) 第 3 批 | 数据来源:上游 #7899 vendor 的 models.dev 快照(31 供应商 JSON,MIT)

## 背景与问题

majo 的种子目录(config/providers.json,33 供应商/175 模型)是 0.4.0
时点的手工快照:新模型要等发版、能力标注(多模态/上下文/免费)会
过时、价格无从谈起(ADR-0017 之前无字段)。上游 #7899 vendor 了
models.dev 社区目录快照(31 个 per-provider JSON、1041 个模型,含
能力/上下文窗口/输出上限/OpenRouter 式价格/thinking 控制面),其
结构可直接作为 majo 的目录更新源。

## 决策

### 1. 数据 vendor

上游快照原样复制到 `resources/config/catalog/providers/`(31 文件,
约 0.9MB),连同 `MODELS_DEV_LICENSE.txt`(models.dev,MIT,保留
归属)。目录随 majo 发版更新(来源:上游 data/ 目录或 models.dev)。

### 2. 同步语义(显式触发,POST /api/models/sync-catalog)

按**供应商 id 精确匹配**现有 providers 行;不匹配的目录文件跳过,
不创建供应商、不改 base_url/api_key/name。每个模型条目:

| 字段 | 已有行 | 新行 |
|---|---|---|
| supports_multimodal/image/video, is_free, probe_source | 覆盖 | 写入 |
| max_input_length | 仅当 `max_input_length_configured=false` | 写入 |
| max_output_length | **不碰**(无 configured 标志,无法与用户手改区分) | 写入 max_tokens |
| pricing(prompt/completion,per-token 字符串) | 有则覆盖(×1M 转 USD/百万,复用 ADR-0017 单位);**无则保留现值不清除** | 同左 |
| thinking_control(kind=effort→efforts;budget→range) | 覆盖 style/options/range 三项**元数据**(UI 形状,用户不可编辑);**绝不碰** thinking_enabled/budget/effort(用户值) | 写入元数据,用户值保持 null |
| supports_tool_calling / supports_audio | 无对应列,忽略(记录在案) | 同 |

结果返回 `{providers_matched, models_updated, models_added,
models_unchanged}`。**永不删除**任何行——目录下线 ≠ 用户失效。

### 3. 目录位置可配置

`majo.catalog.location`(默认 `classpath:config/catalog/providers`),
测试注入 fixture 目录,避免真实 1041 模型进测试库。

## 备选与取舍

- **启动时自动同步**:不可预测(首次启动网络/耗时),改为显式按钮;
- **运行时直连 models.dev API**:桌面离线场景不可靠,vendor 快照为
  唯一事实源;直连可作未来增强;
- **整表替换(删除目录中不存在的模型)**:用户可能已在使用,放弃;
  隐藏(visibility)是已有机制,由用户决定。

## 后果

- 正面:新模型/能力/价格经一次点击进库;ADR-0016 的思维 UI 元数据
  (style/options/range)获得持续更新源;ADR-0017 价格管道有了批量
  数据入口(425 个模型带价)。
- 负面/限制:目录更新仍随发版(非实时);max_tokens 已有行不更新
  (见上表);kilo/openrouter 式定价是牌价,不含缓存/批处理折扣。
- 兼容:纯新增端点与资源,不同步则行为与之前完全一致。

## 验证

CatalogSyncServiceTest(fixture 目录,@SpringBootTest + 内存 H2):
已有行能力/上下文(configured 保护)/价格覆盖与保留、thinking 元数据
覆盖且用户值不动、新行插入、未匹配供应商跳过、max_tokens 已有行
不碰。
