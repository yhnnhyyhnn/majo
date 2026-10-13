/**
 * Vite plugin (qwenpaw #8154 port): inlines the pre-entry startup monitor
 * into index.html BEFORE the module entry, so boot failures that happen
 * before React mounts — a stale hashed entry chunk, a crash at import time
 * — render a readable boot-failure page instead of a blank screen.
 *
 * The monitor is a plain IIFE string (no bundler step): it must stay
 * zero-dependency by construction, which also keeps this plugin free of
 * extra build dependencies.
 */
import type { HtmlTagDescriptor, Plugin } from "vite";

/** sessionStorage key where the last boot error message is kept for diagnosis. */
export const BOOT_ERROR_KEY = "majo_boot_error";

const MONITOR_IIFE = `
(function () {
  var RENDERED = false;
  var RELOAD_KEY = "majo_chunk_reload_count";
  var RELOAD_MAX = 2;
  var RELOAD_WINDOW = 10 * 60 * 1000;
  var MESSAGES = {
    zh: { title: "页面加载失败", reload: "重新加载",
          hint: "通常是更新后的资源缓存问题，重新加载即可；若反复出现请清空浏览器缓存。" },
    en: { title: "Failed to load the page", reload: "Reload",
          hint: "Usually a stale asset cache after an update — reloading fixes it. If it keeps happening, clear the browser cache." },
    ja: { title: "ページの読み込みに失敗しました", reload: "再読み込み",
          hint: "更新後のキャッシュ問題の可能性があります。再読み込みで解決します。繰り返す場合はキャッシュを削除してください。" },
    ru: { title: "Не удалось загрузить страницу", reload: "Перезагрузить",
          hint: "Обычно это кэш после обновления — перезагрузка помогает. Повторяется — очистите кэш браузера." },
    "pt-br": { title: "Falha ao carregar a página", reload: "Recarregar",
          hint: "Geralmente é cache de uma atualização — recarregar resolve. Se persistir, limpe o cache do navegador." },
    vi: { title: "Không tải được trang", reload: "Tải lại",
          hint: "Thường do cache cũ sau khi cập nhật — tải lại là hết. Nếu lặp lại, hãy xóa cache trình duyệt." },
    id: { title: "Gagal memuat halaman", reload: "Muat ulang",
          hint: "Biasanya cache lama setelah pembaruan — muat ulang memperbaikinya. Jika berulang, hapus cache browser." },
  };
  function i18n() {
    var lang = "en";
    try { lang = (localStorage.getItem("language") || navigator.language || "en"); } catch (e) {}
    var lower = String(lang).toLowerCase();
    return MESSAGES[lower] || MESSAGES[lower.split("-")[0]] || MESSAGES.en;
  }
  function reloadState(raw, now) {
    if (!raw) return { count: 0, startedAt: now };
    try {
      var p = JSON.parse(raw);
      if (typeof p.count === "number" && typeof p.startedAt === "number"
          && now - p.startedAt < RELOAD_WINDOW) return p;
    } catch (e) {}
    return { count: 0, startedAt: now };
  }
  function reloadAllowed() {
    try {
      return reloadState(localStorage.getItem(RELOAD_KEY), Date.now()).count < RELOAD_MAX;
    } catch (e) { return false; }
  }
  function markReload() {
    try {
      var now = Date.now();
      var s = reloadState(localStorage.getItem(RELOAD_KEY), now);
      localStorage.setItem(RELOAD_KEY, JSON.stringify({ count: s.count + 1, startedAt: s.startedAt }));
    } catch (e) {}
  }
  function showBootError() {
    if (RENDERED) return;
    RENDERED = true;
    var root = document.getElementById("root");
    if (!root) return;
    var m = i18n();
    root.innerHTML = "";
    var box = document.createElement("div");
    box.style.cssText = "display:flex;flex-direction:column;align-items:center;justify-content:center;"
      + "min-height:100vh;font-family:system-ui,-apple-system,'Segoe UI',sans-serif;gap:14px;padding:24px;text-align:center;";
    var title = document.createElement("div");
    title.textContent = m.title;
    title.style.cssText = "font-size:17px;font-weight:600;opacity:0.85;";
    var hint = document.createElement("div");
    hint.textContent = m.hint;
    hint.style.cssText = "font-size:13px;opacity:0.55;max-width:420px;line-height:1.6;";
    var button = document.createElement("button");
    button.textContent = m.reload;
    button.style.cssText = "padding:7px 22px;border-radius:8px;border:1px solid rgba(128,128,128,0.4);"
      + "background:transparent;font-size:14px;cursor:pointer;";
    button.addEventListener("click", function () { window.location.reload(); });
    box.appendChild(title); box.appendChild(hint); box.appendChild(button);
    root.appendChild(box);
  }
  function markError(what) {
    try {
      sessionStorage.setItem("${BOOT_ERROR_KEY}", String(what instanceof Error ? what.message : what));
    } catch (e) {}
    try {
      if (what instanceof Error && /fetch|import|chunk|preload/i.test(what.name + " " + what.message)
          && reloadAllowed()) {
        markReload();
        window.location.reload();
        return;
      }
    } catch (e) {}
    showBootError();
  }
  window.addEventListener("error", function (e) {
    if (document.querySelector("#root div")) return;
    markError(e.error || e.message || "unknown error");
  });
  window.addEventListener("unhandledrejection", function (e) {
    if (document.querySelector("#root div")) return;
    markError(e.reason || "unhandled rejection");
  });
})();`;

export function startupMonitorPlugin(): Plugin {
  return {
    name: "majo-startup-monitor",
    enforce: "pre",
    transformIndexHtml: () => {
      const tag: HtmlTagDescriptor = {
        tag: "script",
        children: MONITOR_IIFE,
        injectTo: "head-prepend",
      };
      return [tag];
    },
  };
}

/** Exported for tests asserting the inlined behaviour contract. */
export const MONITOR_SOURCE = MONITOR_IIFE;
