import { describe, it, expect, vi, afterEach } from "vitest";

// Regression tests for the pt-BR language selection fix (QwenPaw #7752
// port). nonExplicitSupportedLngs reduces a region-qualified code to its
// language part before matching supportedLngs, so "pt-BR" was looked up
// as "pt", rejected, and the UI silently fell back to English while the
// selector still reported the picked language. Asserting only
// i18n.language cannot catch this: it stayed "pt-BR" while
// resolvedLanguage degraded to "en".
//
// src/i18n.ts initializes the singleton from localStorage at import time,
// so every test needs a fresh module graph.
const freshI18nModule = async () => {
  vi.resetModules();
  return import("./i18n");
};

const freshI18n = async () => {
  const mod = await freshI18nModule();
  const i18n = mod.default;
  if (!i18n.isInitialized) {
    await new Promise((resolve) => i18n.on("initialized", resolve));
  }
  return i18n;
};

describe("i18n regional-variant bundle resolution", () => {
  afterEach(() => {
    localStorage.clear();
    vi.resetModules();
  });

  it("resolves a persisted pt-BR on startup", async () => {
    localStorage.setItem("language", "pt-BR");

    const i18n = await freshI18n();

    expect(i18n.resolvedLanguage).toBe("pt-BR");
    expect(i18n.t("agentStats.agentUsageTitle")).toBe(
      "Uso de tokens por agente",
    );
  });

  it("resolves pt-BR after an explicit switch", async () => {
    const i18n = await freshI18n();

    await i18n.changeLanguage("pt-BR");

    expect(i18n.resolvedLanguage).toBe("pt-BR");
    expect(i18n.t("agentStats.agentUsageTitle")).toBe(
      "Uso de tokens por agente",
    );
  });

  it("keeps region-less languages resolving directly", async () => {
    localStorage.setItem("language", "zh");

    const i18n = await freshI18n();

    expect(i18n.resolvedLanguage).toBe("zh");
    expect(i18n.t("agentStats.agentUsageTitle")).toBe("各 Agent Token 用量");
  });

  it("keeps vi selectable", async () => {
    const i18n = await freshI18n();

    await i18n.changeLanguage("vi");

    expect(i18n.resolvedLanguage).toBe("vi");
  });

  // ── Lazy locale loading (#7829 port) ─────────────────────────────

  it("retries a locale after its first load fails", async () => {
    const mod = await freshI18nModule();
    vi.spyOn(mod.localeLoaders, "zh")
      .mockRejectedValueOnce(new Error("network error"))
      .mockResolvedValueOnce({ common: { loading: "加载中..." } });

    await expect(mod.loadLocale("zh")).rejects.toThrow("network error");
    await expect(mod.loadLocale("zh")).resolves.toEqual({
      common: { loading: "加载中..." },
    });
  });

  it("logs and falls back to English when a locale fails to load", async () => {
    const error = new Error("network error");
    const mod = await freshI18nModule();
    vi.spyOn(mod.localeLoaders, "zh").mockRejectedValueOnce(error);
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);

    const i18n = await (async () => mod.default)();
    await i18n.changeLanguage("zh");

    expect(i18n.resolvedLanguage).toBe("zh");
    expect(consoleError).toHaveBeenCalledWith(
      'Failed to load locale "zh", falling back:',
      error,
    );
    // Fallback to bundled English keeps the UI functional.
    expect(i18n.t("agentStats.agentUsageTitle")).toBe(
      "Token Usage by Agent",
    );
  });
});
