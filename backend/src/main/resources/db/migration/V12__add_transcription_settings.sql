ALTER TABLE settings ADD COLUMN IF NOT EXISTS transcription_provider_type VARCHAR(32) NOT NULL DEFAULT 'disabled';
ALTER TABLE settings ADD COLUMN IF NOT EXISTS transcription_provider_id VARCHAR(64) NOT NULL DEFAULT '';
