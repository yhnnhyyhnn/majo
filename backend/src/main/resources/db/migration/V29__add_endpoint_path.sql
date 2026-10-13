-- ADR-0019: per-provider endpoint path override (OpenAI-compat escape
-- hatch) + the Gemini compatibility-endpoint fix.

ALTER TABLE providers ADD COLUMN IF NOT EXISTS endpoint_path VARCHAR(128) NULL;

-- Repair stock Gemini rows still pointing at the native API root (user
-- customized base_urls are left alone): the OpenAI client appends
-- /v1/chat/completions, so the official compatibility endpoint needs this
-- base + an endpoint-path override.
UPDATE providers
SET base_url = 'https://generativelanguage.googleapis.com/v1beta/openai',
    endpoint_path = '/chat/completions'
WHERE id = 'gemini'
  AND base_url = 'https://generativelanguage.googleapis.com';
