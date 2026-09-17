import { request } from "../request";

export interface ThemeAccentConfig {
  /** Light-mode accent (#rrggbb); empty = default orange. */
  accent?: string;
  /** Dark-mode accent (#rrggbb); empty = follows light. */
  accent_dark?: string;
}

export const themeApi = {
  get: () => request<ThemeAccentConfig>("/config/theme"),

  update: (theme: ThemeAccentConfig) =>
    request<ThemeAccentConfig>("/config/theme", {
      method: "PUT",
      body: JSON.stringify(theme),
    }),

  reset: () => request<ThemeAccentConfig>("/config/theme", { method: "DELETE" }),
};

export const DEFAULT_ACCENT = "#FF7F16";
