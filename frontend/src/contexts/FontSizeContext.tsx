import { createContext, useContext, type ReactNode } from "react";

import { UI_FONT_SIZE_DEFAULT } from "@/utils/uiFontSizePreference";

/**
 * Provides the effective UI font size in px, already resolved from the
 * stored preference. Consumers that need the value in React (e.g. inline
 * styles on vendor components that ignore CSS variables) read this instead
 * of the raw preference.
 */
const FontSizeContext = createContext<number>(UI_FONT_SIZE_DEFAULT);

export function FontSizeProvider({
  value,
  children,
}: {
  value: number;
  children: ReactNode;
}) {
  return (
    <FontSizeContext.Provider value={value}>
      {children}
    </FontSizeContext.Provider>
  );
}

export function useEffectiveFontSize(): number {
  return useContext(FontSizeContext);
}
