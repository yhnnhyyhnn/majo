# Appendix — 附录

> **状态：🚧 部分展开** — [Benchmark Task Set.md](Benchmark%20Task%20Set.md) 已完整撰写，其余文件仍为规划说明。

## 本目录的定位

Appendix 收录不适合放入正式章节体系、但对整体规范理解和落地有价值的补充材料——术语表、参考资料、竞品拆解笔记、基准任务集定义。这些内容通常是持续更新、逐步积累的，而非一次性定稿的正式文档。

## 计划包含的文件

- `Glossary.md` — 术语表，汇总 [README.md](../README.md) 术语约定表之外散落在各 RFC 中定义的专有名词（如 Turn/Trajectory/Context Card/Hunk 等）
- `References.md` — 参考资料链接（Cursor/Claude Code/OpenCode/OpenHands/Codex 的公开文档、技术博客、相关论文）
- `Competitive Notes.md` — 竞品实际使用的详细拆解笔记（比 [Market.md](../01-Product/Market.md) 更细粒度的原始观察记录）
- `Benchmark Task Set.md` ✅ **已完整撰写** — 基准任务集定义，是 [PRD.md §6](../01-Product/PRD.md#6-验收标准总纲) 和各 RFC"验收标准"章节反复引用的测试任务集合，定义了 6 类任务（功能实现/Bug修复/代码问答/长任务自主执行/检索准确率/失败恢复）、评分机制与对比基线方法
- `Interview Guide.md` ✅ **已完整** — 目标用户访谈框架：筛选标准、4阶段12问、映射到规范假设、整理模板、数据汇总方式。为 Persona.md 和 Roadmap.md 的真实用户数据基础。

## 与已完成章节的关联

- `Benchmark Task Set.md` 是本规范中被引用最多但尚未定义的关键依赖——[PRD.md](../01-Product/PRD.md)、[RFC-0001](../03-RFC/RFC-0001%20Agent%20Runtime.md)、[RFC-0002](../03-RFC/RFC-0002%20Context%20Engine.md) 的验收标准都依赖它，应尽快优先填充
- `Competitive Notes.md` 是 [Market.md](../01-Product/Market.md) 完成竞品调研前置工作时产生的原始素材归档地
- `Glossary.md` 应与各 RFC 的"核心概念"章节保持术语一致，发现冲突时以 Glossary 为准并回填修正对应 RFC

## 启动这个目录写作的前置条件

`Benchmark Task Set.md` 已完成撰写。剩余文件的启动条件：
- `Glossary.md` 建议在各 RFC 完整版定稿比例过半后统一汇总，避免过早收集导致后续频繁返工
- `Competitive Notes.md` 依赖 [Market.md](../01-Product/Market.md) 的竞品实测调研启动后同步产出
- `References.md`、`Changelog.md` 可随时增量补充，无强前置依赖
