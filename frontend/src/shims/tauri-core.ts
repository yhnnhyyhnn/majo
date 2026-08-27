/**
 * Drop-in replacement for @tauri-apps/api/core running inside the Electron
 * shell. The preload bridge (electron/preload.cjs) exposes window.majoDesktop
 * with the same invoke/listen shapes, so frontend modules keep their imports.
 */
declare global {
  interface Window {
    majoDesktop?: {
      invoke: (command: string, args?: Record<string, unknown>) => Promise<unknown>;
      listen: (
        channel: string,
        handler: (event: { payload: unknown }) => void,
      ) => Promise<() => void>;
    };
    __TAURI_INTERNALS__?: unknown;
  }
}

export type CommandName = string;

export async function invoke<T>(command: CommandName, args?: Record<string, unknown>): Promise<T> {
  const bridge = window.majoDesktop;
  if (!bridge) {
    throw new Error("majoDesktop bridge is not available in this runtime");
  }
  return bridge.invoke(command, args) as Promise<T>;
}

/** Always true when the Electron preload bridge is present. */
export function isTauri(): boolean {
  return typeof window !== "undefined" && !!window.majoDesktop;
}

export function transformCallback(callback: (response: unknown) => void): number {
  // Legacy Tauri API compatibility; unused by our command surface.
  void callback;
  return -1;
}
