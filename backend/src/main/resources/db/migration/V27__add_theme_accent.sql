-- User-selectable accent colors (QwenPaw #7741 counterpart); null = default orange.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS theme_accent VARCHAR(16);
ALTER TABLE settings ADD COLUMN IF NOT EXISTS theme_accent_dark VARCHAR(16);
