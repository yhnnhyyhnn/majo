CREATE TABLE IF NOT EXISTS inbox_events (
    id            VARCHAR(36)   PRIMARY KEY,
    agent_id      VARCHAR(64)   NOT NULL DEFAULT 'default',
    source_type   VARCHAR(32)   NOT NULL,
    source_id     VARCHAR(64)   NOT NULL DEFAULT '',
    event_type    VARCHAR(32)   NOT NULL,
    status        VARCHAR(16)   NOT NULL,
    severity      VARCHAR(16)   NOT NULL DEFAULT 'info',
    title         VARCHAR(255)  NOT NULL,
    body          CLOB          NOT NULL,
    payload       CLOB,
    read          BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    DOUBLE        NOT NULL,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_inbox_events_agent ON inbox_events(agent_id);
CREATE INDEX IF NOT EXISTS idx_inbox_events_type ON inbox_events(source_type);
CREATE INDEX IF NOT EXISTS idx_inbox_events_status ON inbox_events(status);
CREATE INDEX IF NOT EXISTS idx_inbox_events_read ON inbox_events(read);
CREATE INDEX IF NOT EXISTS idx_inbox_events_created ON inbox_events(created_at DESC);
