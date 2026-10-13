import { beforeEach, describe, expect, it } from "vitest";
import {
  isChunkLoadError,
  recordChunkReload,
  resetChunkReloadCount,
  shouldReloadAfterChunkError,
} from "./chunkRecovery";

/**
 * The bounded-reload contract (#8154): chunk-load failures may trigger at
 * most two automatic reloads inside the window; non-chunk errors never
 * reload; unavailable storage declines (an uncountable loop is worse than
 * a static error).
 */
describe("chunkRecovery", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("recognizes chunk-load errors across Vite/webpack phrasings", () => {
    expect(
      isChunkLoadError(
        new TypeError("Failed to fetch dynamically imported module: /a.js"),
      ),
    ).toBe(true);
    expect(isChunkLoadError(new Error("Loading chunk 5 failed"))).toBe(true);
    expect(isChunkLoadError(new Error("Unable to preload CSS /a.css"))).toBe(
      true,
    );
    expect(isChunkLoadError(new Error("connection reset"))).toBe(false);
    expect(isChunkLoadError("not an object")).toBe(false);
    expect(isChunkLoadError(null)).toBe(false);
  });

  it("allows at most two reloads then declines", () => {
    expect(shouldReloadAfterChunkError()).toBe(true);
    recordChunkReload();
    expect(shouldReloadAfterChunkError()).toBe(true);
    recordChunkReload();
    expect(shouldReloadAfterChunkError()).toBe(false);

    resetChunkReloadCount();
    expect(shouldReloadAfterChunkError()).toBe(true);
  });

  it("window expiry restores the allowance", () => {
    const now = Date.now();
    recordChunkReload(now);
    recordChunkReload(now);
    // 11 minutes later the window has passed.
    expect(shouldReloadAfterChunkError(now + 11 * 60 * 1000)).toBe(true);
  });

  it("malformed counter JSON is treated as unusable, not permissive", () => {
    localStorage.setItem(
      "majo_chunk_reload_count",
      "{count broken",
    );
    // JSON.parse throws → storage effectively unusable → decline (no loop
    // risk). A structurally valid but oddly-typed payload stays permissive:
    // the count just reads as 0.
    expect(shouldReloadAfterChunkError()).toBe(false);
    localStorage.setItem(
      "majo_chunk_reload_count",
      JSON.stringify({ count: "many" }),
    );
    expect(shouldReloadAfterChunkError()).toBe(true);
  });
});
