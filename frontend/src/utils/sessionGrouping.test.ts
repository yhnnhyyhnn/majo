import { describe, it, expect } from "vitest";

import {
  getDateGroup,
  groupSessions,
  groupSessionsByChannel,
} from "./sessionGrouping";

const t = (key: string, optsOrFallback?: Record<string, unknown>): string => {
  if (optsOrFallback && typeof optsOrFallback.defaultValue === "string") {
    return optsOrFallback.defaultValue;
  }
  return key;
};

const iso = (daysAgo: number) => {
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  return d.toISOString();
};

const noopT = ((key: string, optsOrFallback?: unknown) =>
  typeof optsOrFallback === "string"
    ? optsOrFallback
    : key) as unknown as Parameters<typeof groupSessions>[1];

describe("getDateGroup", () => {
  it("buckets by calendar recency", () => {
    expect(getDateGroup(iso(0))).toBe("today");
    expect(getDateGroup(iso(3))).toBe("week");
    expect(getDateGroup(iso(10))).toBe("month");
    expect(getDateGroup(iso(45))).toBe("older");
    expect(getDateGroup(null)).toBe("older");
    expect(getDateGroup("not-a-date")).toBe("older");
  });
});

describe("groupSessions (date mode)", () => {
  it("keeps pinned sessions in their own leading group", () => {
    const groups = groupSessions(
      [
        { id: "a", pinned: true, updatedAt: iso(40) },
        { id: "b", updatedAt: iso(0) },
        { id: "c", updatedAt: iso(45) },
      ],
      noopT,
    );
    expect(groups.map((g) => g.key)).toEqual(["pinned", "today", "older"]);
    expect(groups[0].sessions.map((s) => s.id)).toEqual(["a"]);
  });
});

describe("groupSessionsByChannel (channel mode, #7788 port)", () => {
  it("buckets sessions by channel with console first and pinned leading", () => {
    const groups = groupSessionsByChannel(
      [
        { id: "a", pinned: true, channel: "telegram" },
        { id: "b", channel: "" },
        { id: "c", channel: "wechat" },
        { id: "d", channel: "console" },
        { id: "e", channel: null },
      ],
      t,
    );
    expect(groups.map((g) => g.key)).toEqual([
      "pinned",
      "console",
      "wechat",
    ]);
    expect(groups[1].sessions.map((s) => s.id)).toEqual(["b", "d", "e"]);
    expect(groups[2].sessions.map((s) => s.id)).toEqual(["c"]);
  });

  it("keeps insertion order when labels are unavailable", () => {
    const groups = groupSessionsByChannel(
      [
        { id: "a", channel: "zz-custom" },
        { id: "b", channel: "aa-custom" },
      ],
      t,
    );
    expect(groups.map((g) => g.key)).toEqual(["aa-custom", "zz-custom"]);
  });
});
