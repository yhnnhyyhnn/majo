-- Heartbeat active-hours window (ADR-0013 follow-up): null = unrestricted.
ALTER TABLE settings ADD COLUMN IF NOT EXISTS heartbeat_active_hours_start VARCHAR(5);
ALTER TABLE settings ADD COLUMN IF NOT EXISTS heartbeat_active_hours_end VARCHAR(5);
