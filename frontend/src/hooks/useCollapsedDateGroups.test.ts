import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";

import { useCollapsedDateGroups } from "./useCollapsedDateGroups";

describe("useCollapsedDateGroups", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it("starts with the given initial groups on fresh storage", () => {
    const { result } = renderHook(() =>
      useCollapsedDateGroups(["month", "older"]),
    );
    expect(result.current.collapsedGroups.has("month")).toBe(true);
    expect(result.current.collapsedGroups.has("older")).toBe(true);
    expect(result.current.collapsedGroups.has("today")).toBe(false);
  });

  it("persists toggles and survives a remount", () => {
    const first = renderHook(() => useCollapsedDateGroups(["older"]));
    act(() => first.result.current.toggleGroup("today"));
    expect(first.result.current.collapsedGroups.has("today")).toBe(true);

    // A fresh mount reads the persisted set, not the initial default.
    const second = renderHook(() => useCollapsedDateGroups([]));
    expect(second.result.current.collapsedGroups.has("today")).toBe(true);
    expect(second.result.current.collapsedGroups.has("older")).toBe(true);
  });

  it("toggling twice returns to the initial state", () => {
    const { result } = renderHook(() => useCollapsedDateGroups([]));
    act(() => result.current.toggleGroup("week"));
    act(() => result.current.toggleGroup("week"));
    expect(result.current.collapsedGroups.has("week")).toBe(false);
    expect(localStorage.getItem("majo_collapsed_date_groups_v1")).toBe(
      JSON.stringify([]),
    );
  });
});
