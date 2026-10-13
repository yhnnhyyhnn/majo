import { describe, expect, it } from "vitest";
import { BOOT_ERROR_KEY, MONITOR_SOURCE, startupMonitorPlugin } from "../../scripts/startupMonitorPlugin";

/**
 * Contract of the inlined pre-entry monitor (#8154): it installs global
 * error/rejection listeners, keeps a bounded reload for chunk-shaped
 * errors, renders a localized boot-failure page otherwise, and steps
 * aside once React has mounted (#root has content).
 */
describe("startupMonitorPlugin", () => {
  it("produces a head-prepend script transform", () => {
    const plugin = startupMonitorPlugin();
    expect(plugin.name).toBe("majo-startup-monitor");
    const transform = (plugin as unknown as {
      transformIndexHtml: () => Array<{ tag: string; injectTo: string }>;
    }).transformIndexHtml;
    const tags = transform();
    expect(tags).toHaveLength(1);
    expect(tags[0].tag).toBe("script");
    expect(tags[0].injectTo).toBe("head-prepend");
  });

  it("inlined source installs listeners and honours the reload cap", () => {
    expect(MONITOR_SOURCE).toContain('addEventListener("error"');
    expect(MONITOR_SOURCE).toContain('addEventListener("unhandledrejection"');
    expect(MONITOR_SOURCE).toContain('document.querySelector("#root div")');
    expect(MONITOR_SOURCE).toContain(RELOAD_MAX_LITERAL);
    expect(MONITOR_SOURCE).toContain(BOOT_ERROR_KEY);
    // All 7 boot locales present.
    for (const marker of [
      "页面加载失败",
      "Failed to load the page",
      "ページの読み込み",
      "Не удалось загрузить",
      "Falha ao carregar",
      "Không tải được trang",
      "Gagal memuat halaman",
    ]) {
      expect(MONITOR_SOURCE).toContain(marker);
    }
  });
});

const RELOAD_MAX_LITERAL = "RELOAD_MAX = 2";
