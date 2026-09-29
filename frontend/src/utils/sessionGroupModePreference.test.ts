import { describe, it, expect, beforeEach, afterEach } from "vitest";

import {
  getSessionGroupModePreference,
  setSessionGroupModePreference,
  SESSION_GROUP_MODE_CHANGE_EVENT,
  type SessionGroupMode,
} from "./sessionGroupModePreference";

describe("sessionGroupModePreference", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it("defaults to channel grouping (#7972)", () => {
    expect(getSessionGroupModePreference()).toBe("channel");
  });

  it("persists and reads back a valid mode", () => {
    setSessionGroupModePreference("channel");
    expect(localStorage.getItem("majo_session_group_mode")).toBe("channel");
    expect(getSessionGroupModePreference()).toBe("channel");
  });

  it("falls back to the default on an invalid stored value", () => {
    localStorage.setItem("majo_session_group_mode", "bogus");
    expect(getSessionGroupModePreference()).toBe("channel");
  });

  it("announces changes through a window event", () => {
    const seen: SessionGroupMode[] = [];
    const onChange = () => seen.push(getSessionGroupModePreference());
    window.addEventListener(SESSION_GROUP_MODE_CHANGE_EVENT, onChange);
    try {
      setSessionGroupModePreference("none");
    } finally {
      window.removeEventListener(SESSION_GROUP_MODE_CHANGE_EVENT, onChange);
    }
    expect(seen).toEqual(["none"]);
  });
});
