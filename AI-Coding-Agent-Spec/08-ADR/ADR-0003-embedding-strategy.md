# ADR-0003：Context Engine Embedding 策略

## Status

Accepted

## Context

[RFC-0002 Context Engine](../03-RFC/RFC-0002%20Context%20Engine.md) 的索引构建和语义检索依赖 Embedding 模型生成代码片段的向量表示。[RFC-0002 §11 开放问题](../03-RFC/RFC-0002%20Context%20Engine.md#11-开放问题) 提出核心选择：**本地部署 Embedding 模型**（保护代码隐私但增加本地资源消耗） vs **调用云端 Embedding API**（零本地开销但代码片段会上传云端）。

影响本决策的关键因素：

1. **[Product Philosophy](../00-Vision/Product%20Philosophy.md) 核心信条**："本地优先，云端增强。开发者的代码是最敏感的资产之一。默认本地执行、本地存储，云端能力是可选的增强。"
2. **性能可行性**：2024-2025 年的本地 Embedding 模型（如 all-MiniLM-L6-v2、bge-small-en）在消费级硬件上已可达到 < 10ms 延迟，且模型体积小（~100MB），内存占用可接受
3. **索引构建速度**：本地模型处理 10 万行代码的 initial indexing 预计在 1-5 分钟内完成（取决于机器性能）——对于首次使用时的异步后台任务这是可接受的
4. **索引质量**：云端 Embedding 模型（如 OpenAI text-embedding-3）通常维度更高、质量更好，但代价是代码片段离开用户机器

## Decision

**v1.0 默认使用本地轻量 Embedding 模型；云端 Embedding API 作为可选的增强配置，用户可自行切换。**

理由：

1. 符合 [Product Philosophy](../00-Vision/Product%20Philosophy.md)"本地优先"核心信条——默认设置应保护用户代码隐私，不能让"首次索引全量代码上传云端"成为默认行为
2. 本地轻量模型（推荐 `all-MiniLM-L6-v2`，384 维）的质量向量在代码检索任务上与云端模型的差距已在工业界被多次验证为可接受——Recall 差距通常在 3-5% 以内，且在 Reranking 阶段可以通过符号匹配和依赖图查询弥补语义检索的不足
3. 若用户明确信任云端且追求更高质量索引，可配置切换到 OpenAI `text-embedding-3-small` 等模型——这个选择权在用户手里，不在产品默认行为里

**备选策略（未采用的理由）**：
- **v1.0 只用云端**：违背"本地优先"核心信条，且对无网络环境的用户不可用
- **只支持本地**：剥夺了愿意用云端模型获取更高质量索引的用户的选择权——产品不应替用户做"云端 vs 本地"的价值判断

## Consequences

### 正面影响

- 默认行为完全离线，首次索引不涉及外传代码（符合 [Product Philosophy](../00-Vision/Product%20Philosophy.md)）
- 索引检索延迟 < 10ms（本地模型推理），不受网络延迟波动影响
- 用户体验更简单——首次使用不需要配置 Embedding API Key，开箱即可用

### 负面权衡

- 本地 Embedding 模型需要额外 ~100MB 磁盘空间和 ~200MB 内存
- 语义检索质量略低于云端模型（但差距在可接受范围，且 [RFC-0002 §5](../03-RFC/RFC-0002%20Context%20Engine.md#5-检索结果融合与-reranking) 的三路检索 + Reranking 设计已考虑了此差距的弥补）
- 需要维护本地 Embedding 模型的分发和管理（模型文件怎么打包、怎么更新）——建议独立于应用二进制分发，首次使用时自动下载

### 对已有 RFC 的影响

- [RFC-0002 Context Engine](../03-RFC/RFC-0002%20Context%20Engine.md) 的 §4 索引构建流程无需修改——Embedding 是一个可替换的组件，本地 vs 云端只是不同的实现，不影响上层接口设计
- [RFC-0002 §10 验收标准](../03-RFC/RFC-0002%20Context%20Engine.md#10-验收标准) 的 Recall ≥ 80% 指标在本地 Embedding 方案下仍然适用——这个指标定义时就考虑了本地模型的质量上限
- [Deployment.md](../02-Architecture/Deployment.md) 需要添加本地 Embedding 模型的分发方案
