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
  zh: `### Majo 0.2.0 更新亮点

- 长期记忆闭环:自动记忆提炼(LLM 摘要)、/memory 命令、自动召回
- Coding Mode 实际生效,新增 lsp 与 ast_search 代码智能工具
- 心跳调度:按周期执行 HEARTBEAT.md 任务,支持活动时段窗口与最近渠道回传
- 控制台:自定义主题色、分组会话分页、多文件夹默认工作区、已发送文件抽屉
- 多项正确性修复:命令超时强制生效、进程树清理、启动迁移不再覆盖配置

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

  ru: `### Основные изменения в Majo 0.2.0

- Замкнутый цикл долгосрочной памяти: авто-извлечение (LLM-резюме), команда /memory, автоматический recall
- Coding Mode реально работает, новые инструменты lsp и ast_search
- Планировщик Heartbeat: периодические задачи из HEARTBEAT.md, окно активности, доставка в последний канал
- Консоль: настраиваемый цвет темы, пагинация групп сессий, мульти-папочные рабочие каталоги, панель отправленных файлов
- Серия исправлений: принудительный таймаут команд, очистка дерева процессов, миграция больше не перезаписывает конфигурацию

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

  en: `### Majo 0.2.0 highlights

- Long-term memory loop: automatic LLM distillation, /memory command, auto recall
- Coding Mode now takes effect at runtime, with new lsp and ast_search code-intelligence tools
- Heartbeat scheduler: periodic HEARTBEAT.md tasks with active-hours window and last-channel delivery
- Console: customizable accent color, grouped session pagination, multi-folder default workspaces, sent-files drawer
- Multiple correctness fixes: enforced command timeouts, process-tree cleanup, startup migration no longer overwrites config

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
