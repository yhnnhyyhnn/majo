/**
 * Majo Desktop preload bridge.
 *
 * Exposes `window.majoDesktop` with the same command names the previous
 * Tauri shell registered, so frontend modules (backendRuntime.ts,
 * CloseWindowPrompt.tsx, desktopUpdate.ts, ...) keep their call shapes.
 * Events are delivered through `on(channel, handler)` returning an
 * unsubscribe function — mirroring @tauri-apps/api/event listen().
 */
const { contextBridge, ipcRenderer } = require("electron");

/** Tauri-style invoke: (cmd: string, args?: Record<string, unknown>) => Promise. */
function invoke(command, args = {}) {
  // Update commands have no Electron implementation yet (no release feed);
  // resolve to the same "nothing available" shape the Tauri stub returned.
  if (command === "check_desktop_update" || command === "check_cached_update") {
    return Promise.resolve(null);
  }
  if (
    command === "install_desktop_update" ||
    command === "download_desktop_update" ||
    command === "install_downloaded_update"
  ) {
    return Promise.reject(new Error("Updates are not available in this build"));
  }
  const channel = "majo_" + command;
  return ipcRenderer.invoke(channel, args ?? {});
}

/** Tauri-style listen: subscribe to a main-process event. */
function listen(channel, handler) {
  const wrapped = (_event, payload) => handler({ event: channel, payload, id: -1 });
  ipcRenderer.on(channel, wrapped);
  return Promise.resolve(() => {
    ipcRenderer.removeListener(channel, wrapped);
  });
}

contextBridge.exposeInMainWorld("majoDesktop", {
  invoke,
  listen,
});
