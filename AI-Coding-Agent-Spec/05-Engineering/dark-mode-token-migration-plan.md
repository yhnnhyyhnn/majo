# 全站深色模式 Token 迁移计划(0.3.x 批次)——✅ 已完成(2026-09-18)

> **完成状态**:B1-B5 全部落地,共 token 化基底 **488 处**、裁剪冗余
> 暗色规则 **100+ 条**(净 -1000 余行);剩余基底裸值 **107 处**为难啃
> 特殊上下文(渐变内嵌、未映射 hex、常暗面板刻意值),已记录在案不再
> 迁移。工具:`frontend/scripts/dev/tokenize-alpha.cjs`(属性感知
> token 化)、`prune-dark-report.cjs`(冗余暗色规则判定与裁剪)、
> `scripts/dev/darkmode-survey.cjs`(普查,区分基底裸值与合法补丁)。
> 方法沉淀见文末"两步法"与经验教训。

2026-09-18 普查:`src/**/*.less` 中仍有 **1321 处裸黑白 alpha**
(`rgba(0,0,0|x|26,23,22|43,18,0|234,232,231)` 且不在 `var(--majo-*, …)`
回退内),分布在 67 个文件。另有少量 var 回退(无害,随 token
迁移自然统一)与常暗面板白 alpha(应保留,见下)。

## 两步法与经验教训(B1 试错沉淀)

1. **B1a 基底先行,暗色补丁不动**:浅色值 token 等值替换,深色仍由
   原补丁渲染——视觉零风险;补丁随之语义冗余但无害。
2. **B1b 按覆盖判定裁剪**:仅当基底对同选择器以 token 化值覆盖同
   属性集时才删;antd 内部覆盖/玻璃基底/accent 发散规则自动保留。
3. 教训:毛玻璃 `rgba(255,255,255,x)+backdrop-filter` 是设计而非
   主题色,不可 token 化;CRLF 行尾会让 JS 正则 `.` 失配(需先剥
   `\r`);跨行多选择器规则(`.a,\n.b {`)不得裁剪(曾致 ApprovalCard
   结构损坏,已加固并全量重做)。

## 迁移规则(沿用 index.module.less 两批的既定做法)

1. 黑白 alpha 按语义映射 antd token:
   `0.88→color-text`、`0.65→color-text-secondary`、`0.45→color-text-tertiary`、
   `0.25/0.35→color-text-quaternary`、`0.02→fill-quaternary`、`0.04→fill-tertiary`、
   `0.06→fill-secondary`、`0.12+→fill`、边框→`border(-secondary)`;
   原值保留为 `var(--majo-*, 原值)` 回退。
2. **激活/选中态的深色 accent 高亮配对保留为显式规则**(深色用
   accent 色调、浅色用中性色调是刻意设计)。
3. 常暗面板(品牌色侧栏、代码编辑器深色主题区)内的白 alpha 保留。
4. 每批迁移后:vite build + 前端全量测试 + 浏览器深浅双主题抽查。

## 分批计划(按裸引用数排序)

| 批次 | 文件 | 裸引用 | 备注 |
|---|---|---|---|
| B1 | pages/Settings/Models | 110 | 最大单文件 |
| B1 | pages/Agent/Skills | 97 | |
| B1 | pages/Settings/SkillPool | 76 | 3 处 dark 块 |
| B2 | pages/Control/Channels | 60 | |
| B2 | pages/Agent/MCP | 56 | |
| B2 | pages/Control/CronJobs | 53 | |
| B2 | components/AgentSelector | 44 | |
| B3 | pages/Agent/Workspace | 43 | 5 处 dark 块 |
| B3 | pages/Coding/FilePreview + GitPanel + TabbedEditor + FileTree + index | 175 | 编辑器区多为常暗,逐个甄别 |
| B4 | Settings/Security (38/9 dark 块) + Agents 系列 | ~90 | dark 块最密集 |
| B5 | Inbox/ModelSelector/Environments/SessionItem/Sessions/ToolCards/Files 等 | ~240 | 收尾扫尾 |

每批一个提交;完成后更新本表并复跑普查脚本(临时
`scripts/dev/darkmode-survey.cjs` 可复现)。
