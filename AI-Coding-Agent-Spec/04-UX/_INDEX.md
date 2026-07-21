# 04-UX — 目录说明

> **状态：🚧 待展开** — 本目录当前仅有此说明文件，尚未产出具体内容。

## 本目录的定位

04-UX 负责把 [01-Product](../01-Product/User%20Journey.md) 的用户旅程和 [Information Architecture](../01-Product/Information%20Architecture.md) 落地为具体的交互设计——终端 UI 的视觉呈现、编辑器插件的界面布局、Diff 审查界面的具体交互细节。这是本产品与 Cursor/Claude Code/OpenCode 在"体验层"直接竞争的地方，见 [README.md](../README.md) 中对标产品对比表。

## 计划包含的文件

- `Terminal UI.md` — CLI 交互界面的视觉设计规范（进度展示、Diff 渲染、确认交互的终端渲染方案）
- `IDE Extension UI.md` — VS Code 插件面板布局、与原生编辑器 UI 的融合设计
- `Review Interaction.md` — Diff 逐块审查交互的详细设计（对应 [RFC-0007 Review](../03-RFC/RFC-0007%20Review.md)）
- `Autonomy Configuration UI.md` — 渐进自主性配置的交互设计（对应 [RFC-0015 Permission](../03-RFC/RFC-0015%20Permission.md)）
- `Design System.md` — 视觉规范（终端配色方案、图标体系、组件库）

## 与已完成章节的关联

- [User Journey.md](../01-Product/User%20Journey.md) 定义的各阶段旅程需要在此落地为具体界面
- [Information Architecture.md](../01-Product/Information%20Architecture.md) 定义的信息层级是本目录设计的直接输入
- [RFC-0007 Review](../03-RFC/RFC-0007%20Review.md)、[RFC-0015 Permission](../03-RFC/RFC-0015%20Permission.md) 的交互状态机需要具体的视觉呈现方案
- 呼应 [Product Philosophy](../00-Vision/Product%20Philosophy.md) 可解释性支柱——UI 设计的核心目标是让 Agent 行为"可观察、不神秘"

## 启动这个目录写作的前置条件

- 需要 [Information Architecture.md](../01-Product/Information%20Architecture.md) 完成定稿，明确核心信息层级
- 建议先做低保真线框图并做用户可用性测试，再定稿本目录的正式设计规范
- CLI 与 IDE 插件的优先级需要先在 [Roadmap.md](../01-Product/Roadmap.md) 中明确，决定本目录哪部分先启动
