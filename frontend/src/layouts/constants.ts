// ── URLs ──────────────────────────────────────────────────────────────────

export const PYPI_URL = "https://pypi.org/pypi/qwenpaw/json";

export const GITHUB_URL = "https://github.com/yhnnhyyhnn/majo" as const;

// ── Timing ────────────────────────────────────────────────────────────────

export const ONE_HOUR_MS = 60 * 60 * 1000;

// ── URL helpers ───────────────────────────────────────────────────────────

export const getWebsiteLang = (lang: string): string =>
  lang.startsWith("zh") ? "zh" : "en";

export const getDocsUrl = (lang: string): string =>
  `https://qwenpaw.agentscope.io/docs/intro?lang=${getWebsiteLang(lang)}`;

export const getFaqUrl = (lang: string): string =>
  `https://qwenpaw.agentscope.io/docs/faq?lang=${getWebsiteLang(lang)}`;

export const getReleaseNotesUrl = (lang: string): string =>
  `https://qwenpaw.agentscope.io/release-notes?lang=${getWebsiteLang(lang)}`;

export const getFeatureDemosUrl = (lang: string): string =>
  `https://qwenpaw.agentscope.io/docs/functiondemo?lang=${getWebsiteLang(
    lang,
  )}`;

// ── Version helpers ────────────────────────────────────────────────────────

// Filter out pre-release versions; post-releases are treated as stable.
// PEP 440 pre-release suffixes: aN / bN / rcN (or cN) / devN.
export const isStableVersion = (v: string): boolean =>
  !/(\d)(a|alpha|b|beta|rc|c|dev)\d*/i.test(v);

// Compare two PEP 440 version strings. Returns >0 if a>b, <0 if a<b, 0 if equal.
// .postN releases sort after their base version (e.g. 1.0.0.post1 > 1.0.0).
// Pre-release versions (aN, bN, rcN) sort before their base version.
export const compareVersions = (a: string, b: string): number => {
  const normalise = (v: string): number[] => {
    // Handle .postN suffix
    const postMatch = v.match(/\.post(\d+)$/i);
    const postNum = postMatch ? Number(postMatch[1]) : 0;
    const baseVersion = v.replace(/\.post\d+$/i, "");

    // Handle pre-release suffix (e.g., 1.0.1b1 -> base=1.0.1, preType=b, preNum=1)
    const preMatch = baseVersion.match(/^(.+?)(a|alpha|b|beta|rc|c)(\d*)$/i);
    let coreVersion = baseVersion;
    let preType = 0; // 0 = stable, -3 = alpha, -2 = beta, -1 = rc
    let preNum = 0;
    if (preMatch) {
      coreVersion = preMatch[1];
      const preLabel = preMatch[2].toLowerCase();
      preType =
        preLabel === "a" || preLabel === "alpha"
          ? -3
          : preLabel === "b" || preLabel === "beta"
          ? -2
          : -1; // rc or c
      preNum = preMatch[3] ? Number(preMatch[3]) : 0;
    }

    const parts = coreVersion.split(/[.\-]/).map((seg) => Number(seg) || 0);
    // Append: preType (0 for stable, negative for pre-release), preNum, postNum
    return [...parts, preType, preNum, postNum];
  };

  const aN = normalise(a);
  const bN = normalise(b);
  const len = Math.max(aN.length, bN.length);
  for (let i = 0; i < len; i++) {
    const diff = (aN[i] ?? 0) - (bN[i] ?? 0);
    if (diff !== 0) return diff;
  }
  return 0;
};

// ── Update markdown ───────────────────────────────────────────────────────
export const UPDATE_MD: Record<string, string> = {
  zh: `### Majo 0.3.0 更新亮点


- 死循环防护:同一工具以相同参数反复调用时自动警告并拒绝,不再空烧 token
- 记忆索引自动刷新:直接编辑 memory/ 文件后检索即时生效,无需手动重建
- 外部 Agent 委派支持后台运行:立即返回 task_id,进度与结果用 check_agent_task 查询
- 控制台:Agent 统计页新增各 Agent Token 用量表;侧栏会话列表支持按时间/按渠道/平铺三种分组
- 界面:语义化设计 token 全面落地,深色模式大量手工覆盖块退役;修复 pt-BR 语言选择失效

### 如何更新

1. 源码部署:拉取最新代码后重新构建

\`\`\`
git pull origin master
mvn -f backend/pom.xml package
cd frontend && npm ci && npm run build
\`\`\`

2. Docker 部署:拉取最新镜像并重启

\`\`\`
docker compose pull
docker compose up -d
\`\`\`

升级后重启后端服务即可。`,

  ru: `### Основные изменения в Majo 0.3.0

- Защита от бесконечных циклов: повторные вызовы одного инструмента с теми же аргументами предупреждаются и отклоняются
- Индекс памяти обновляется автоматически: правки файлов memory/ сразу видны поиску
- Делегирование внешнему агенту в фоне: мгновенный task_id, прогресс и результат через check_agent_task
- Консоль: таблица расхода токенов по агентам в статистике; три режима группировки сессий (по дате / по каналу / плоский)
- Интерфейс: семантические дизайн-токены внедрены повсеместно, множество ручных тёмных переопределений удалено; исправлен выбор языка pt-BR

### Как обновить

1. Из исходников: получите изменения и пересоберите

\`\`\`
git pull origin master
mvn -f backend/pom.xml package
cd frontend && npm ci && npm run build
\`\`\`

2. Docker: загрузите новый образ и перезапустите

\`\`\`
docker compose pull
docker compose up -d
\`\`\`

После обновления перезапустите бэкенд.`,

  en: `### Majo 0.3.0 highlights

- Doom-loop protection: repeated identical tool calls are warned and denied instead of burning tokens
- Memory index auto-refresh: edits to memory/ files are picked up by search immediately, no manual rebuild
- External agent delegation in the background: returns a task_id instantly; poll progress and results via check_agent_task
- Console: per-agent token usage table in Agent Statistics; session list grouping by date / channel / flat
- UI: semantic design tokens fully adopted, large swaths of manual dark-mode overrides retired; pt-BR language selection fixed

### How to update

1. Source deployment: pull the latest code and rebuild

\`\`\`
git pull origin master
mvn -f backend/pom.xml package
cd frontend && npm ci && npm run build
\`\`\`

2. Docker deployment: pull the latest image and restart

\`\`\`
docker compose pull
docker compose up -d
\`\`\`

After upgrading, restart the backend service.`,
};
