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
  zh: `### Majo 如何更新

要更新 Majo 到最新版本，可根据你的安装方式选择对应方法：

1. 如果你从源码运行，拉取最新代码后重新构建：

\`\`\`
git pull origin master
mvn -f backend/pom.xml package
cd frontend && npm ci && npm run build
\`\`\`

2. 如果你使用 Docker，拉取最新镜像并重启容器：

\`\`\`
docker compose pull
docker compose up -d
\`\`\`

升级后重启后端服务即可。`,

  ru: `### Как обновить Majo

Чтобы обновить Majo, выберите способ в зависимости от типа установки:

1. Если вы запускаете Majo из исходников, получите последние изменения и пересоберите:

\`\`\`
git pull origin master
mvn -f backend/pom.xml package
cd frontend && npm ci && npm run build
\`\`\`

2. Если используете Docker, загрузите новый образ и перезапустите контейнеры:

\`\`\`
docker compose pull
docker compose up -d
\`\`\`

После обновления перезапустите бэкенд.`,

  en: `### How to update Majo

To update Majo, use the method matching your installation type:

1. If you run Majo from source, pull the latest code and rebuild:

\`\`\`
git pull origin master
mvn -f backend/pom.xml package
cd frontend && npm ci && npm run build
\`\`\`

2. If using Docker, pull the latest image and restart the containers:

\`\`\`
docker compose pull
docker compose up -d
\`\`\`

After upgrading, restart the backend service.`,
};
