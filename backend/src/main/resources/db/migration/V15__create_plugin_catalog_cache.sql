CREATE TABLE IF NOT EXISTS plugin_catalog_cache (
    catalog_key  VARCHAR(64)   PRIMARY KEY,
    content      CLOB          NOT NULL,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
