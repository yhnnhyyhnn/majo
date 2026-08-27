/**
 * Drop-in replacement for @tauri-apps/plugin-dialog inside the Electron
 * shell. Delegates to main-process native dialogs through the preload bridge.
 */
interface SaveOptions {
  defaultPath?: string;
}

export async function save(options?: SaveOptions): Promise<string | null> {
  const bridge = (window as unknown as {
    majoDesktop?: {
      invoke: (command: string, args?: Record<string, unknown>) => Promise<unknown>;
    };
  }).majoDesktop;
  if (!bridge) {
    throw new Error("majoDesktop bridge is not available in this runtime");
  }
  const result = await bridge.invoke("majo_show_save_dialog", {
    defaultPath: options?.defaultPath ?? "",
  });
  return typeof result === "string" ? result : null;
}
