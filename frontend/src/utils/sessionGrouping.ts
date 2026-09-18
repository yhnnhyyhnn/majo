/**
 * Shared session grouping utilities for both SidebarSessionList and ChatSessionDrawer.
 * Groups sessions by date: pinned, today, within 7 days, within 30 days, older.
 */

import { getChannelLabel } from "../pages/Control/Channels/components/constants";

export type DateGroup = "pinned" | "today" | "week" | "month" | "older";

export interface SessionGroup<T> {
  key: DateGroup;
  label: string;
  sessions: T[];
}

/** A session group keyed by an arbitrary string (channel groups, flat list). */
export interface StringKeyedSessionGroup<T> {
  key: string;
  label: string;
  sessions: T[];
}

/**
 * Determine which date group a timestamp belongs to.
 * Uses calendar dates (not elapsed-time) so "today" always means the same Y/M/D.
 */
export function getDateGroup(
  timestamp: string | null | undefined,
): Exclude<DateGroup, "pinned"> {
  if (!timestamp) return "older";
  const date = new Date(timestamp);
  if (isNaN(date.getTime())) return "older";

  const now = new Date();
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const dateStart = new Date(
    date.getFullYear(),
    date.getMonth(),
    date.getDate(),
  );
  const calendarDays = Math.floor(
    (todayStart.getTime() - dateStart.getTime()) / (1000 * 60 * 60 * 24),
  );

  if (calendarDays <= 0) return "today";
  if (calendarDays < 7) return "week";
  if (calendarDays < 30) return "month";
  return "older";
}

/**
 * Group sessions by pinned status and date.
 * Pinned sessions go to "pinned" group, others are grouped by date.
 * Empty groups are filtered out.
 */
export function groupSessions<
  T extends {
    pinned?: boolean;
    updatedAt?: string | null;
    createdAt?: string | null;
  },
>(
  sessions: T[],
  t: (key: string, fallback: string) => string,
): SessionGroup<T>[] {
  const buckets: Record<DateGroup, T[]> = {
    pinned: [],
    today: [],
    week: [],
    month: [],
    older: [],
  };

  for (const s of sessions) {
    if (s.pinned) {
      buckets.pinned.push(s);
    } else {
      buckets[getDateGroup(s.updatedAt ?? s.createdAt)].push(s);
    }
  }

  const order: Array<{ key: DateGroup; fallback: string }> = [
    { key: "pinned", fallback: "Pinned" },
    { key: "today", fallback: "Today" },
    { key: "week", fallback: "Within 7 days" },
    { key: "month", fallback: "Within 30 days" },
    { key: "older", fallback: "Earlier" },
  ];

  return order
    .filter(({ key }) => buckets[key].length > 0)
    .map(({ key, fallback }) => ({
      key,
      label: t(`chat.group.${key}`, fallback),
      sessions: buckets[key],
    }));
}

/**
 * Group sessions by messaging channel (QwenPaw #7788 "source" mode,
 * majo's analog of chat groups). Pinned sessions keep their own leading
 * group; sessions without a channel fall into the "console" bucket; the
 * remaining buckets follow the Console group in label order.
 */
export function groupSessionsByChannel<
  T extends {
    pinned?: boolean;
    channel?: string | null;
  },
>(
  sessions: T[],
  t: (key: string, opts?: Record<string, unknown>) => string,
): Array<StringKeyedSessionGroup<T>> {
  const channelOf = (s: T): string => {
    const raw = (s.channel ?? "").trim();
    return raw || "console";
  };

  const pinned: T[] = [];
  const buckets = new Map<string, T[]>();
  for (const s of sessions) {
    if (s.pinned) {
      pinned.push(s);
      continue;
    }
    const key = channelOf(s);
    const bucket = buckets.get(key);
    if (bucket) bucket.push(s);
    else buckets.set(key, [s]);
  }

  const labelOf = (key: string): string =>
    key === "console"
      ? t("channels.channelNames.console", { defaultValue: "Console" })
      : getChannelLabel(key);

  const groups: Array<StringKeyedSessionGroup<T>> = [];
  if (pinned.length > 0) {
    groups.push({
      key: "pinned",
      label: t("chat.group.pinned", { defaultValue: "Pinned" }),
      sessions: pinned,
    });
  }
  if (buckets.has("console")) {
    groups.push({
      key: "console",
      label: labelOf("console"),
      sessions: buckets.get("console")!,
    });
  }
  const rest = [...buckets.keys()]
    .filter((key) => key !== "console")
    .sort((a, b) => labelOf(a).localeCompare(labelOf(b)));
  for (const key of rest) {
    groups.push({ key, label: labelOf(key), sessions: buckets.get(key)! });
  }
  return groups;
}
