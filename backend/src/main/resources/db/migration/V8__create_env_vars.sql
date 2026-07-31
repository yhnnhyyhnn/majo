CREATE TABLE env_vars (
    env_key VARCHAR(255) PRIMARY KEY,
    env_value VARCHAR(4096) NOT NULL DEFAULT ''
);
