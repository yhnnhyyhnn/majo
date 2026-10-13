import {
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import {
  applyUiFontSizeToRoot,
  clampUiFontSize,
  getUiFontScale,
  getUiFontSize,
  setUiFontSize,
  subscribeUiFontSize,
  UI_FONT_SIZE_DEFAULT,
  UI_FONT_SIZE_MAX,
  UI_FONT_SIZE_MIN,
} from "./uiFontSizePreference";

/**
 * The #8005 font-size mechanism: a localStorage-backed external store with
 * clamping, cross-tab sync, and root application that is a strict no-op at
 * the default size.
 */
describe("uiFontSizePreference", () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.style.fontSize = "";
    document.documentElement.style.removeProperty("--majo-font-scale");
  });

  it("defaults and clamps to the supported range", () => {
    expect(UI_FONT_SIZE_DEFAULT).toBe(14);
    expect(getUiFontSize()).toBe(14);
    expect(clampUiFontSize(9)).toBe(UI_FONT_SIZE_MIN);
    expect(clampUiFontSize(99)).toBe(UI_FONT_SIZE_MAX);
    expect(clampUiFontSize(Number.NaN)).toBe(UI_FONT_SIZE_DEFAULT);
    expect(clampUiFontSize(15.6)).toBe(16);
  });

  it("persists non-default sizes and removes the key at default", () => {
    setUiFontSize(17);
    expect(localStorage.getItem("majo_ui_font_size")).toBe("17");
    expect(getUiFontSize()).toBe(17);

    setUiFontSize(14);
    expect(localStorage.getItem("majo_ui_font_size")).toBeNull();
    expect(getUiFontSize()).toBe(14);
  });

  it("notifies subscribers on change and on cross-tab storage events", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeUiFontSize(listener);

    setUiFontSize(16);
    expect(listener).toHaveBeenCalled();

    window.dispatchEvent(
      new StorageEvent("storage", { key: "majo_ui_font_size" }),
    );
    expect(listener).toHaveBeenCalledTimes(2);

    // Unrelated keys do not notify.
    listener.mockClear();
    window.dispatchEvent(new StorageEvent("storage", { key: "other" }));
    expect(listener).not.toHaveBeenCalled();

    unsubscribe();
  });

  it("applies root percent and scale, no-op at default", () => {
    applyUiFontSizeToRoot(18);
    expect(document.documentElement.style.fontSize).toBe("128.571%");
    expect(document.documentElement.style.getPropertyValue("--majo-font-scale")).toBe(
      String(getUiFontScale(18)),
    );
    expect(getUiFontScale(18)).toBeCloseTo(18 / 14, 3);

    applyUiFontSizeToRoot(14);
    expect(document.documentElement.style.fontSize).toBe("100%");
    expect(document.documentElement.style.getPropertyValue("--majo-font-scale")).toBe("1");
  });
});
