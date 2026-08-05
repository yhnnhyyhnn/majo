-- Extend plugin_cache with platform market fields (source='market' rows).
ALTER TABLE plugin_cache ADD COLUMN display_name VARCHAR(255);
ALTER TABLE plugin_cache ADD COLUMN developer VARCHAR(255);
ALTER TABLE plugin_cache ADD COLUMN owner VARCHAR(128);
ALTER TABLE plugin_cache ADD COLUMN logo_url VARCHAR(512);
ALTER TABLE plugin_cache ADD COLUMN downloads BIGINT;
ALTER TABLE plugin_cache ADD COLUMN view_count BIGINT;
ALTER TABLE plugin_cache ADD COLUMN details_url VARCHAR(512);
ALTER TABLE plugin_cache ADD COLUMN is_featured BOOLEAN DEFAULT FALSE;
ALTER TABLE plugin_cache ADD COLUMN compat_labels CLOB;
ALTER TABLE plugin_cache ADD COLUMN locales CLOB;
ALTER TABLE plugin_cache ADD COLUMN sort_rank INTEGER DEFAULT 0;
