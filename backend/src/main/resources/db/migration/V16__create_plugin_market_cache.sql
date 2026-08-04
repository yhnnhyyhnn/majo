CREATE TABLE IF NOT EXISTS plugin_market_cache (
    cache_key    VARCHAR(64)   PRIMARY KEY,
    content      CLOB          NOT NULL,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
