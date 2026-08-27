-- Hidden flag for discovered provider models (model visibility toggle)
ALTER TABLE provider_models ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE;
