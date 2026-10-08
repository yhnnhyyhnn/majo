// ── URLs ──────────────────────────────────────────────────────────────────

export const GITHUB_URL = "https://github.com/yhnnhyyhnn/majo" as const;

// Latest non-prerelease, non-draft release of THIS repository. The web
// update check reads it unauthenticated; with no published release yet the
// endpoint 404s and the check degrades to "no update". (Fork residue: this
// used to point at the qwenpaw PyPI json, comparing Majo's version against
// the reference project's and flagging an update forever.)
export const LATEST_RELEASE_API = `${GITHUB_URL.replace(
  "github.com",
  "api.github.com/repos",
)}/releases/latest`;

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
  zh: `### Majo 0.4.0 更新亮点


- 沙箱执行隔离(ADR-0012 二期): execute_command 可在 Windows AppContainer 容器内运行——仅授权路径可达、特权被削减、网络默认关闭, 越界读写经真实验证均被拒绝; 配置页新增沙箱开关, 修改即时生效
- Embedding 记忆后端(ADR-0015): 语义向量+关键词双路召回, 中文查询可命中英文记忆; 未配置嵌入服务时自动回退关键词检索
- 记忆后端切换即时生效: 修复切换 memory_manager_backend 后旧后端缓存滞留到重启的问题(#7893)
- 死循环防护阈值可配置: Security 页新增配置标签(security.doom_loop)
- 控制台与桌面: 会话列表默认按渠道分组; 数学公式渲染修复; 停止后工具卡不再悬挂; 桌面安装包改由 tag 自动构建并签名

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

  ru: `### Основные изменения в Majo 0.4.0

- Изоляция выполнения в песочнице (ADR-0012, этап 2): execute_command может работать внутри Windows AppContainer — доступны только разрешённые пути, привилегии урезаны, сеть по умолчанию закрыта; выход за границы проверен на реальной машине. В настройках агента появился переключатель песочницы, изменения применяются сразу
- Embedding-бэкенд памяти (ADR-0015): двухпутевой поиск — семантические векторы + ключевые слова, запрос на китайском находит англоязычные воспоминания; без настройки embedding автоматически используется ключевой поиск
- Переключение бэкенда памяти действует сразу: исправлено зависание старого бэкенда в кэше до перезапуска (#7893)
- Настраиваемая защита от бесконечных циклов: новая вкладка на странице Security (security.doom_loop)
- Консоль и десктоп: список сессий по умолчанию группируется по каналу; исправлен рендеринг формул; карточки инструментов больше не зависают после остановки; установщик Windows собирается и подписывается автоматически по тегу

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

  en: `### Majo 0.4.0 highlights

- Sandbox execution isolation (ADR-0012 phase 2): execute_command can run inside a Windows AppContainer — only granted paths are reachable, privileges are stripped, network is closed by default; out-of-bounds access verified denied on a real host. New sandbox toggle in Agent Config, effective immediately
- Embedding memory backend (ADR-0015): dual-path semantic + keyword recall — a Chinese query can hit English memories; falls back to keyword search automatically when no embedding endpoint is configured
- Memory backend switches take effect immediately: fixed the old backend serving from cache until restart after changing memory_manager_backend (#7893)
- Configurable doom-loop protection: new tab on the Security page (security.doom_loop)
- Console & desktop: session list groups by channel by default; math rendering fixed; tool cards no longer hang after a stop; the Windows installer is now built and signed automatically from tags

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
