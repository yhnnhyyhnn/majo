import { useCallback, useState } from "react";

const STORAGE_KEY = "majo_collapsed_date_groups_v1";

function loadCollapsed(): Set<string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        return new Set(parsed);
      }
    }
  } catch {
    // Keep the safe default when storage is unavailable.
  }
  return new Set();
}

function saveCollapsed(groups: Set<string>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...groups]));
  } catch {
    // Collapse state can remain memory-only.
  }
}

/**
 * Collapsed state of the sidebar session list sections (date buckets or
 * channel groups — keys are opaque strings). Persisted so the layout
 * survives remounts and reloads (QwenPaw #7788 port).
 *
 * @param initialCollapsed groups collapsed before the user expresses a
 * preference (a fresh storage) — majo ships with "month"/"older" folded.
 */
export function useCollapsedDateGroups(initialCollapsed: string[] = []) {
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(() => {
    const stored = loadCollapsed();
    if (stored.size > 0) return stored;
    return new Set(initialCollapsed);
  });

  const toggleGroup = useCallback((key: string) => {
    setCollapsedGroups((previous) => {
      const next = new Set(previous);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      saveCollapsed(next);
      return next;
    });
  }, []);

  const expandGroup = useCallback((key: string) => {
    setCollapsedGroups((previous) => {
      if (!previous.has(key)) return previous;
      const next = new Set(previous);
      next.delete(key);
      saveCollapsed(next);
      return next;
    });
  }, []);

  return { collapsedGroups, toggleGroup, expandGroup };
}
