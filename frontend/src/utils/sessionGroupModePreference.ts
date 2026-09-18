/**
 * Session list grouping mode preference (QwenPaw #7788 port).
 *
 * Controls how the sidebar conversation history sections sessions:
 * - "date": by recency bucket (pinned/today/week/month/older)
 * - "channel": by messaging channel (console first, then label order)
 * - "none": a single flat recency list
 *
 * Mirrors the localStorage + window-event pattern so every mounted
 * session list stays in sync after a mode change.
 */
export type SessionGroupMode = "date" | "channel" | "none";

const SESSION_GROUP_MODE_STORAGE_KEY = "majo_session_group_mode";
export const SESSION_GROUP_MODE_CHANGE_EVENT =
  "majo:session-group-mode-change";

const DEFAULT_SESSION_GROUP_MODE: SessionGroupMode = "date";

function isSessionGroupMode(value: string | null): value is SessionGroupMode {
  return value === "date" || value === "channel" || value === "none";
}

export function getSessionGroupModePreference(): SessionGroupMode {
  try {
    const stored = localStorage.getItem(SESSION_GROUP_MODE_STORAGE_KEY);
    if (isSessionGroupMode(stored)) {
      return stored;
    }
  } catch {
    // storage unavailable
  }
  return DEFAULT_SESSION_GROUP_MODE;
}

export function setSessionGroupModePreference(mode: SessionGroupMode): void {
  try {
    localStorage.setItem(SESSION_GROUP_MODE_STORAGE_KEY, mode);
  } catch {
    // storage unavailable
  }

  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(SESSION_GROUP_MODE_CHANGE_EVENT));
  }
}
