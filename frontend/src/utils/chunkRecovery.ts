/**
 * Chunk-load failure recognition and guarded reload (qwenpaw #8154 port,
 * trimmed to majo's needs). A deployed build invalidates hashed chunks:
 * a user with an open tab gets ChunkLoadError / dynamic-import failures on
 * navigation. The fix is a bounded automatic reload — bounded so a truly
 * broken deployment cannot turn into a refresh loop.
 *
 * Zero-dependency and side-effect free; consumed by the startup monitor
 * (inline) and usable from React error boundaries.
 */

const RELOAD_COUNT_KEY = "majo_chunk_reload_count";
const RELOAD_COUNT_MAX = 2;
/** Window in which consecutive reloads count toward the cap (10 minutes). */
const RELOAD_WINDOW_MS = 10 * 60 * 1000;

/** Structural duck-type for errors crossing unknown boundaries. */
export interface ErrorLike {
  message?: unknown;
  name?: unknown;
}

/** Messages Vite/Rollup emit when a hashed chunk cannot be fetched. */
const CHUNK_ERROR_PATTERNS = [
  "failed to fetch dynamically imported module",
  "error loading dynamically imported module",
  "importing a module script failed",
  "unable to preload css",
  "chunkloaderror",
  "loading chunk",
  "loading css chunk",
];

/** Whether the error is a chunk-load failure (message- and name-based). */
export function isChunkLoadError(error: unknown): boolean {
  if (error === null || typeof error !== "object") {
    return false;
  }
  const e = error as ErrorLike;
  const haystack = `${e.name ?? ""} ${e.message ?? ""}`.toLowerCase();
  return CHUNK_ERROR_PATTERNS.some((p) => haystack.includes(p));
}

/**
 * Whether an automatic reload may happen now. Counts reloads inside the
 * window against the cap; a count past the cap returns false, and storage
 * being unavailable also declines — an uncountable loop is worse than a
 * static error.
 */
export function shouldReloadAfterChunkError(now: number = Date.now()): boolean {
  try {
    const raw = localStorage.getItem(RELOAD_COUNT_KEY);
    if (!raw) {
      return true;
    }
    const parsed = JSON.parse(raw) as { count?: unknown; startedAt?: unknown };
    if (
      typeof parsed.count === "number" &&
      typeof parsed.startedAt === "number" &&
      now - parsed.startedAt < RELOAD_WINDOW_MS
    ) {
      return parsed.count < RELOAD_COUNT_MAX;
    }
    // Missing/expired window: fresh allowance.
    return true;
  } catch {
    return false; // storage unavailable: cannot bound the loop, don't reload
  }
}

/** Record one automatic reload (call right before location.reload()). */
export function recordChunkReload(now: number = Date.now()): void {
  let count = 0;
  let startedAt = now;
  try {
    const raw = localStorage.getItem(RELOAD_COUNT_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as { count?: unknown; startedAt?: unknown };
      if (
        typeof parsed.count === "number" &&
        typeof parsed.startedAt === "number" &&
        now - parsed.startedAt < RELOAD_WINDOW_MS
      ) {
        count = parsed.count;
        startedAt = parsed.startedAt;
      }
    }
    localStorage.setItem(
      RELOAD_COUNT_KEY,
      JSON.stringify({ count: count + 1, startedAt }),
    );
  } catch {
    // storage unavailable: the next shouldReload check will decline
  }
}

/** Reset the reload counter (call after a successful app mount). */
export function resetChunkReloadCount(): void {
  try {
    localStorage.removeItem(RELOAD_COUNT_KEY);
  } catch {
    // storage unavailable
  }
}
