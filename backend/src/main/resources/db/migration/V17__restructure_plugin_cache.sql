DROP TABLE IF EXISTS plugin_catalog_cache;
DROP TABLE IF EXISTS plugin_market_cache;

CREATE TABLE IF NOT EXISTS sync_configs (
    config_key     VARCHAR(64)   PRIMARY KEY,
    url            VARCHAR(512)  NOT NULL,
    last_synced_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS plugin_cache (
    id             VARCHAR(128)  PRIMARY KEY,
    source         VARCHAR(16)   NOT NULL,
    plugin_id      VARCHAR(128)  NOT NULL,
    name           VARCHAR(255),
    description    CLOB,
    version        VARCHAR(32),
    author         VARCHAR(255),
    kind           VARCHAR(32),
    size_bytes     BIGINT,
    display_size   VARCHAR(32),
    install_url    VARCHAR(512),
    sha256         VARCHAR(128),
    category       VARCHAR(64),
    cached_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_plugin_cache_source ON plugin_cache(source);
CREATE INDEX IF NOT EXISTS idx_plugin_cache_name ON plugin_cache(name);
