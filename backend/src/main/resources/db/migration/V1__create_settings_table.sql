CREATE TABLE IF NOT EXISTS settings (
    id INT PRIMARY KEY CHECK (id = 1),
    api_key VARCHAR(512) NOT NULL DEFAULT '',
    base_url VARCHAR(512) NOT NULL DEFAULT 'https://api.openai.com/v1',
    model_name VARCHAR(256) NOT NULL DEFAULT 'gpt-4o-mini'
);

-- Ensure single row exists with defaults
MERGE INTO settings (id, api_key, base_url, model_name)
    KEY (id)
    VALUES (1, '', 'https://api.openai.com/v1', 'gpt-4o-mini');
