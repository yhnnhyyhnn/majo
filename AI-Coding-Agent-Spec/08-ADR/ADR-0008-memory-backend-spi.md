# ADR-0008：记忆后端 SPI 化——按 QwenPaw 插件接口设计多后端记忆层

## Status

Accepted（2026-09-13）

## Context

majo 当前没有真正的长期记忆后端：

- QwenPaw 2.1 时代的 ReMe 向量记忆在 Java 重实现时被替换为**零依赖关键词倒排索引**（`MemoryIndexService`，扫描 workspace `memory/` 目录），只服务于前端的 rebuildMemoryIndex 动作——它是一个"索引演示"，不是记忆系统：没有记忆写入生命周期、没有召回工具暴露给模型、没有自动检索。
- running-config 里保留了完整的 `reme_light_memory_config` / `memory_manager_backend` 占位（`RunningConfigDefaults`），但无任何代码消费。
- 与此同时 QwenPaw 2.2.x 已把记忆演进为**插件注册的多后端体系**（`5b1fa7ce` 把 ADBPG/PowerContext 迁为插件、`9635901d` 引入 PowerContext、`65502ee3` 统一生命周期与动作接口、`1174c6d9` 统一 ReMe 斜杠命令）：`BaseMemoryManager` 抽象 + `MemoryBackendRegistry` 注册表 + 插件 `register_memory_backend` API，后端不可用时安全回退（`2b09de79`）。

如果 majo 直接补一个向量检索实现，只会复刻 QwenPaw 已放弃的单体路线；如果什么都不做，配置占位持续误导。正确的路径是按 QwenPaw 已验证的**多后端 SPI**架构对齐，让"哪个后端"变成可替换的实现细节。

## Decision

**新增 `memory` 包：`MemoryBackend` SPI + `MemoryBackendRegistry` 注册表 + 现有关键词索引改造为默认实现（`KeywordMemoryBackend`）。不实现向量/embedding 后端——那是未来某个 SPI 实现的事。**

### 接口契约（对齐 QwenPaw `BaseMemoryManager` 的核心语义）

```java
public interface MemoryBackend {
    String id();                                   // "keyword" / 未来 "reme" / "powercontext" ...
    void start(MemoryBackendContext ctx);          // 初始化存储
    void close();                                  // 刷新并释放
    String getMemoryPrompt();                      // 注入系统提示的记忆指引（可为空串）
    List<MemoryHit> search(String query, int maxResults);   // 召回
    void remember(String content, Map<String,Object> metadata); // 写入
    boolean isAvailable();                         // 后端健康（不可用时注册表回退到下一个）
}
```

- `MemoryBackendContext`（agent_id、workspace、backend_config、language）对齐 QwenPaw 的 `MemoryBackendContext`。
- `MemoryHit`（source、snippet、score）对齐召回结果的最小公共面。
- **`MemoryBackendRegistry`**：按 id 注册/查找，`resolve(agentId)` 按配置选择后端，选中后端 `!isAvailable()` 时**按注册顺序回退**（QwenPaw #7544/#7663 的降级语义）。
- 后端实现为 Spring bean，注册表启动时收集所有 `MemoryBackend` bean——Java 侧用 Spring 生命周期替代 QwenPaw 的插件 `register_memory_backend` 动态注册（majo 无插件运行时，等价且更简单；未来引入插件化时注册表接口不变）。

### 与运行时的接线（复用既有基础设施）

1. **`memory_search` 工具**：新增 `MemorySearchTool`，调用 `registry.resolve(agentId).search(...)`，让模型能主动召回。
2. **自动检索**：`ModelRequestNormalizerHook` 在构建请求前，若配置启用 auto_memory_search（现有 `reme_light_memory_config.auto_memory_search_config.enabled`），把用户消息交给 `registry.resolve(...).search(...)`，命中结果以一条系统 reminder 消息注入请求（不落历史）。
3. **reindex 端点语义不变**：`/agents/{id}/memory/reindex` 变为调用 `registry.resolve(agentId).rebuild()`——对 keyword 后端就是重建倒排索引，API 契约零变化。
4. **配置**：`memory_manager_backend` 从占位变为真实选择器；未知值回退 `keyword`。

### 明确不做（本 ADR 范围外）

- 不实现 ReMe/PowerContext/ADBPG 后端——SPI 就绪后可独立引入
- 不做自动记忆提取（auto_memory 写入管道）——需要 LLM 调用编排，单独 RFC
- 不做记忆斜杠命令统一（`1174c6d9`）——依赖动作接口，后续跟进
- 向量检索/embedding 管理——同上，属于某个具体后端的实现细节

## Consequences

### 正面影响

- 记忆从"索引演示"升级为可插拔架构，与 QwenPaw 2.2.x 的后端注册模型同构，未来引入任何后端（包括向量）都是新增一个 bean
- `memory_search` 工具 + 自动检索让记忆第一次真正进入 agent 能力面
- 现有 API（reindex/graph）契约零破坏

### 负面权衡

- keyword 后端能力有限（无语义召回）——但明确优于此前"无召回"，且 SPI 让升级成本独立于本架构
- 自动检索为每次模型调用增加一次索引查询——keyword 索引为内存 Map，开销可忽略；换向量后端时需重新评估

### 对已有规范的影响

- `MemoryIndexService` 的索引逻辑迁入 `KeywordMemoryBackend`，原类删除（调用点同步迁移）
- ADR-0003（embedding 策略）中向量部分延后至具体向量后端引入时修订
