# QwenPaw 同步扫描日志

对上游 agentscope-ai/QwenPaw 的周期性评估记录（参照系:每次扫描时的
majo master）。原则:只移植 majo 架构下可验证、有真实价值的变更;
上游专属子系统(Hub/creator/pawapp/telemetry/数据应用)与绑定其
vendor SDK 内部的修复一律跳过。

## 2026-09-22 扫描(main @ c11c9d7c,新增 8 提交)

- **#7909 KaTeX 数学渲染 → 已移植**(一行 CSS 引入):majo 的聊天
  渲染器 @ant-design/x-markdown 会产出 katex 数学结构且 katex JS
  已随传递依赖存在,缺的只是基础样式表。
- #7919 DoomLoop 升级需新证据 → 无需移植:上游修的是其"轮询式
  check 可重复计数"的架构问题;majo 的 PRE_ACTING 单次调用即新
  证据,设计天然免疫。其"按消息 id 去重记录"的思路已记录。
- #7899 供应商统一(模型发现/定价/选择/思维控制,265 文件) →
  暂缓:大型特性,需先盘点 majo Providers 页的能力差距再立项。
- #7846 会话列表详情与分组增强 → 后续候选(悬停卡显示分组、
  会话名长度上限;与 majo 已有分组模式可叠加)。
- #7902/#7897/#7829 → 见 9/21 扫描(#7829 已移植)。
- 604f51f0 AgentScope 2.0.8 bump → 关注项:majo 锁定本地 2.0.0
  jar,升级需整体验证,暂缓。
- 8af8b6e3 docs 清理 / a461a95a 覆盖率冲刺 / 7df447de 发布 CI →
  上游专属,跳过。

## 2026-09-21 扫描(main @ 24567462,新增 10 提交)

- **#7345 工具卡停止后悬挂 calling → 已移植**:新增
  ToolCallTurnContext(turn 结束信号,Chat 页以 !chatLoading 提供),
  v1Adapter 派生状态收口悬挂调用为 interrupted;ToolCardShell 区分
  "中断"与"工具失败";i18n 全 7 语言;4 个新单测。
- #7902 文件标签激活时刷新缓存 → 后续候选:其 FilesWorkspace 与
  majo 的 codingTabsStore 结构不同,需单独评估 majo 编辑器是否有
  同类陈旧缓存问题。
- #7897 项目目录选择器改进 → 后续候选(其 FilesWorkspace 专属)。
- #7829 聊天依赖拆分 + locale 懒加载 → 后续候选(纯性能,majo 打包
  1.7MB 主 chunk 同样受益)。
- #6399 ReMe reranker 配置面板 → 跳过:其 ReMe 记忆子系统专属,
  majo 记忆后端无 reranker 概念。
- #7886 input_audio 拒绝处理 → 低价值(边界错误处理)。
- #7894 纯测试(覆盖 Statement+1027)/ #7863 Windows CI 稳定 /
  #7901 发布 CI / v2.2.2b4 bump → 上游专属,跳过。

## 2026-09-18 扫描(main @ 549a7f3c,新增 12 提交)

- **#7852 技能池批量广播 → 已移植**(cbedbef):批量模式工具栏新增
  "广播 (N)"按钮,预填广播模态为勾选技能;广播模态本就支持多技能。
- #7760 关机时记忆任务排空 → 跳过:针对其 Windows CLI 关机流程;
  majo 的"待写轮次持久化跨重启"已覆盖同类故障。
- #7834 /compact 发到当前会话 → 跳过:其 ChatPage 与 vendor SDK
  (chatRef/execution)的特定集成问题,majo 聊天架构不同。
- #7851 恢复助手响应操作 → 跳过:上游对自己此前自定义重新生成按钮
  的回退,majo 没有该自定义。
- #7831 后台工具输出按需流式 → 跳过:其 BackgroundTaskPanel 专属;
  majo 后台任务走 check_agent_task 轮询,语义不同。
- **#7685 飞书折叠推理面板 → 暂缓(记录在案)**:依赖飞书卡片消息
  管线(cardkit:card:write 权限、流式卡片更新);majo 飞书渠道当前
  仅发纯文本(msg_type: text)。若未来做"飞书卡片输出"特性批次,
  此项为第一批内容。无飞书工作区无法验证,不盲移。
- #7833 Hub 修复 / #7637 数据应用 0.3.0 / #7862 发布 CI /
  #7860 e2e 选择器 / v2.2.2b2·b3 版本 bump → 上游专属,跳过。

## 2026-09-18 早间扫描(main @ 1d5021a4,12 提交)

- **#7752 pt-BR 语言选择修复 → 已移植**(40bee86,含回归测试)。
- **#7788 侧栏会话列表分组模式 → 已移植**(eaf081b,三种分组 +
  折叠持久化;小屏 CSS 细节未搬)。
- #7808 DoomLoop 配置重构 → 触发 **DoomLoopGate 移植**(b3b2c9a,
  后经 6fbe005/f76acd5 配置化 + 前端标签)。
- #7751 Docker 运行时对齐 → majo 核对后天然一致,无需处理。
- #7488 pawapp-sdk / #7779 Hub 网关 / #7802 遥测 / #7823 creator /
  #7819 e2e 等待 / #7803 CI 超时 → 上游专属,跳过。
- #7826 供应商创建对话框 → majo 已在 v0.2.0 批次覆盖同等能力。
