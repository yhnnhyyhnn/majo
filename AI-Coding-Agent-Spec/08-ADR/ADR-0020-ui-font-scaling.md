# ADR-0020: 全局界面字号缩放(机制层 + 样式 token 化)

日期:2026-10-13 | 状态:已采纳 | 上游参照:QwenPaw #8005(机制移植)

## 背景与问题

界面字号固定 px:高分屏/视力差异用户无法调整;上游 #8005 用"根字号
百分比 + 语义字号 token + antd token 联动"解决了同类问题,机制层与
业务组件零耦合,可整体移植。

## 决策

1. **偏好存储**:localStorage(`majo_ui_font_size`),12–20px 整数
   clamp;默认值 14px 存为"键不存在";跨 tab 经 storage 事件同步;
   `useSyncExternalStore` 订阅。
2. **应用方式**:paint 前(useLayoutEffect)设置根 `font-size` 百分比
   (继承型文本缩放,不与 antd 显式 px 叠加)+ `--majo-font-scale`
   变量(自研样式按 `calc(<基线>px * var(--majo-font-scale))` 接入)。
3. **antd token**:`fontSize` + controlHeight 三档随缩放增长(放大档
   控件盒不憋字,缩小档保持默认点击目标)。默认值下一切严格 no-op。
4. **样式 token 化**:`styles/tokens.css` 定义 `--majo-font-{xs..title}`
   十档(基线=历史固定 px);79 个 less 模块 776 处 `font-size` 由
   codemod(scripts/dev/tokenize-font-sizes.mjs,保留供防回潮复跑)
   全量替换为 token 引用。
5. **入口**:侧栏设置面板字号滑条,实时生效;i18n 全 7 语言。

## 备选与取舍

- **浏览器缩放**(Ctrl+滚轮):缩放布局而非字号,破坏布局;弃;
- **仅 antd token 不做样式 token 化**:自研模块字号不随动,体验半吊;
- **服务器持久化**:纯展示偏好,本地存储足够。

## 后果

- 正面:全界面(antd + 自研)字号随动;默认态逐像素不变(零回归);
  新增固定 px 可复跑 codemod 防回潮。
- 负面/限制:新代码仍可能写固定 px(codemod 兜底);极小/极大档位下
  个别非字号尺寸(图标盒)未随动,接受。

## 验证

preference 单测 4 用例(clamp/持久化/订阅/根应用 no-op);完整构建;
浏览器实测三态:默认 no-op、18px 时 token 解析 calc(20px*1.286)、
antd 按钮同步 18px。
