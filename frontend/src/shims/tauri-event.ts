/**
 * Drop-in replacement for @tauri-apps/api/event running inside the Electron
 * shell. Delegates to the preload bridge's listen(channel, handler), which
 * returns an unsubscribe function just like the Tauri API.
 */
export type UnlistenFn = () => void;

export interface Event<T> {
  event: string;
  id: number;
  payload: T;
}

type EventCallback<T> = (event: Event<T>) => void;

interface Bridge {
  invoke: (command: string, args?: Record<string, unknown>) => Promise<unknown>;
  listen: (
    channel: string,
    handler: (event: { payload: unknown }) => void,
  ) => Promise<() => void>;
}

function bridge(): Bridge | undefined {
  return (window as unknown as { majoDesktop?: Bridge }).majoDesktop;
}

/** Subscribe to a main-process event; resolves with an unlisten function. */
export async function listen<T = unknown>(
  eventName: string,
  handler: EventCallback<T>,
): Promise<UnlistenFn> {
  const b = bridge();
  if (!b) {
    throw new Error("majoDesktop bridge is not available in this runtime");
  }
  const unlisten = await b.listen(eventName, (event) => {
    handler(event as Event<T>);
  });
  return unlisten;
}

/**
 * Emit from the renderer. Not used by the Majo frontend (all commands go
 * through invoke), provided for API compatibility.
 */
export async function emit(eventName: string, payload?: unknown): Promise<void> {
  const b = bridge();
  if (!b) throw new Error("majoDesktop bridge is not available in this runtime");
  await b.invoke("__emit", { eventName, payload });
}
