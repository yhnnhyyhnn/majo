# QwenPaw 同步扫描日志

对上游 agentscope-ai/QwenPaw 的周期性评估记录（参照系:每次扫描时的
majo master）。原则:只移植 majo 架构下可验证、有真实价值的变更;
上游专属子系统(Hub/creator/pawapp/telemetry/数据应用)与绑定其
vendor SDK 内部的修复一律跳过。

## 2026-10-13 扫描(main @ 02b6a6d0,新增 29 提交)

- **#8188 审批决定保留与超时竞态 → 已移植**:majo 全中其修复的竞态——
  等待方超时不落决定,迟到 approve 假成功(agent 实际已拒绝);resolve
  非原子 check-then-act;无 409 语义;过期卡永留 pending 列表
  (purgeExpired 生产无人调)。移植:settle 以 CompletableFuture.complete
  的 first-writer-wins 为唯一仲裁;过期请求即使收到批准也落
  denied/timeout;await 超时经同一仲裁收敛、竞胜的决定不被降级,并把
  等待 clamp 到请求 deadline;listPending 顺带清扫过期卡;approve/deny
  对已决/过期返回 409(findAny 查 history 区分 404)。前端配套:request
  错误附带 HTTP status,Chat/Inbox 对 409 就地移除失效卡并提示
  "已被处理或已过期"(i18n 全 7 语言),不再误报失败。
- **#8136 图片缩放保留 EXIF 方向 → 已移植**:majo 的 view_image 缩放
  重编码路径(ImageIO+Graphics2D)不应用 EXIF Orientation,手机竖拍
  JPEG 重编码后横躺且元数据已丢。自研零依赖实现 ImageExif:解析
  APP1/IFD0 tag 274(小端/大端/畸形一律退化为方向 1),重编码前用
  AffineTransform 把方向烤进像素(desktop_screenshot 为 Robot 截图、
  view_video 为 ffmpeg 帧,均无 EXIF,不涉及)。
- **#8010 媒体载荷拒绝后恢复 → 已移植(适配)**:上游修复"坏图片块从
  历史重放导致会话永远 400"。majo 的 MediaPromotionHook 同样把工具图
  片提升进持久上下文,同病。移植:MediaPayloadRecovery 按上游口径分类
  (媒体名词+载荷信号,排除内容安全/溢出措辞),命中后重试一次并 arm
  strip 标志;ModelRequestNormalizerHook 在 PRE_REASONING 对请求剥离
  媒体(历史不动,同上游 formatter strip 语义)。strip 标志按 sessionId
  键控而非 agent 名——HarnessAgent 每轮重建、名字跨聊天共享,而毒化
  历史只在一个会话里(上游的 agent 实例≈会话,故其按实例粘滞;10-13
  晚间加固为会话键控并经 ToolGuardHook 注入 RuntimeContext)。上游的
  "能力损失不得缓存"在 majo 无对应物(能力查元数据,无失败缓存),略。
- #7893 / #8119 → 已在 9/30-10/01 间先行移植(d2f74b8 / 37c6023)。
- #8083 view_audio 工具 → 跳过:majo 无音频通路(无 AudioBlock 产生
  路径,/workspace/transcribe 为桩),属新特性批次而非修复,待音频
  管线立项时一并评估。
- #7869 连接检查带会话头 → 跳过:其 OpenCode 供应商(Go 网关要求
  MissingSessionID)专属;majo 无 session header 概念。
- #8090 新版 GPT token 参数识别 → 跳过:上游修的是自组请求体的
  openai_provider;majo 请求体由 agentscope SDK jar 组装,max_tokens
  仅是模型元数据字段,无落点。
- #8050 DST 感知进程时区 → 跳过:上游修"把当前固定偏移套到反 DST
  半年的历史时间戳";majo 转录时间戳全程 LocalDateTime 存取,无带
  时区换算步骤,天然不涉及。
- #8055 技能池下载孤儿清扫 → 跳过:其 stage 目录在池内故需启动清扫;
  majo 的 staging 走系统临时目录(扫描不覆盖、OS 清理策略管理),
  失败路径已有 finally 清理,孤儿无害。
- #8157 聊天复制图标尺寸 → 跳过:其 vendor SDK(requestActions)
  专属;majo 聊天前端自研。
- #8032 PTY 高描述符 → 跳过:Python select.select→poll 专属,majo
  无终端服务。
- #8169/#8168 Hub / #8155 QwenPaw-Flash 模型推荐 / #8141 数据应用 /
  #8154/#8149/#8159/#8145/#8130/#8127/#8146/#8144 console UI 批次 →
  上游专属或按 9/29 既定策略暂缓;#8041/#8043/#8038/#8039/#8037
  CI/e2e → 上游专属。
- 后续跟进:9/22 暂缓的 #7899 供应商统一已完成能力差距盘点,见
  [qwenpaw-7899-provider-gap.md](qwenpaw-7899-provider-gap.md)——
  majo 缺的是执行层最后一公里(思维控制未接请求/无原生适配/无价格),
  按 4 批独立立项。**第 1 批"思维控制接通执行层"已实施**(ADR-0016,
  ModelRoutingService.resolveThinkingOptions 经 GenerateOptions 接线:
  thinking_budget/reasoning_effort/enable_thinking 双保险关断/
  generate_kwargs 含 extra_body 提升/custom_headers 全部生效)。

## 2026-10-01 扫描(main @ 77744172,新增 6 提交)

- **#8025 NSIS 禁用 solid 压缩 → 已移植**:vendor 上游 2.11.4 的
  安装器模板(per-file 压缩替代 /SOLID),解包时的临时数据块磁盘
  峰值不再随安装体积翻倍——对内嵌 76MB JRE 的 majo 尤为相关。
  majo CLI 为 2.11.1,模板为 2.11.4 版;构建+静默安装+启动+卸载
  全链路实测通过后引入。注意:CLI 升级到 >2.11.4 时应重新比对
  官方模板差异。
- #8021 聊天复制图标恢复 → 其字号缩放批次(#8005)的回归修复,
  majo 未引入该批次,不适用。
- #8017 模型设置打磨 → 后续候选(UI 批次暂缓策略同 9/29 结论)。
- #8026/#8003 CI 跨平台路径与沙箱清理 / #7806 博客 → 上游专属。

## 2026-09-29/30 扫描(main @ 16f55cfb,新增 28 提交)

- **#7972 会话列表默认分组改 source → 已移植**(majo 对应默认改为
  channel 模式——majo 的渠道分组即上游 source 的实现,且此前默认
  date 的理由已被分组持久化取代;上游配套把测试显式设为 date)。
- #7919 类修复(工具卡生命周期按执行起点门控 #7971) → 不适用:
  majo 的 ToolCardShell 无生命周期查询/offload 控件。
- #7409 丢弃空 assistant 文本块 → **建议自研**:空 reasoning-only
  块会被部分供应商 400 拒绝并污染会话;majo 保存路径是否落空块
  待查,属独立小任务。
- #7965 Scroll 历史媒体回收 + thinking 省略对齐 token 计数 → 跳过:
  其 Scroll 上下文管理器专属;majo 的上下文三件套架构不同。
- #7910 Windows 命令控制台隔离(CREATE_NO_WINDOW) → 无需移植:
  majo 的 ProcessBuilder 默认无新控制台。
- #7932 打包后模型目录校验 / #7596 FTS 修复与保留清理 / #7933 宠物
  审批 / #7952 Hub 邀请 / #7954 网站公告 → 上游专属。
- #8005 全局字号缩放 / #7956 设置 UX 统一+对话过渡 / #7861 多标签
  聊天终端 / #8016 模态过渡稳定 / #8019 图标恢复+项目标签截断 →
  UI 大批次,暂缓(需逐项与 majo 现有布局比对,避免大改回归)。
- #8006 QQ 网关重放去重 / #7953 导入失败详情 / #8014 模型发现警告
  详情 → 低价值或渠道专属。
- #8004 CLI 懒加载 / #7941 跨平台测试 → 上游 Python CLI 专属。

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
