CREATE TABLE IF NOT EXISTS subscriptions (
    chat_id VARCHAR(64) PRIMARY KEY,
    active BOOLEAN NOT NULL,
    wbs VARCHAR(16) NOT NULL,
    min_area DECIMAL(12, 2),
    max_warm DECIMAL(12, 2)
);

CREATE TABLE IF NOT EXISTS telegram_update_position (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    next_offset BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS search_drafts (
    chat_id VARCHAR(64) PRIMARY KEY,
    step VARCHAR(16) NOT NULL,
    wbs VARCHAR(16) NOT NULL,
    min_area DECIMAL(12, 2),
    max_warm DECIMAL(12, 2)
);

CREATE TABLE IF NOT EXISTS command_replies (
    chat_id VARCHAR(64) PRIMARY KEY,
    update_id BIGINT NOT NULL,
    reply CLOB NOT NULL
);

ALTER TABLE search_drafts ADD COLUMN IF NOT EXISTS single_field BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS delivery_events (
    event_key VARCHAR(512) PRIMARY KEY,
    payload CLOB,
    created_at BIGINT NOT NULL,
    completed_at BIGINT,
    message_text CLOB,
    map_png BLOB,
    map_approximate BOOLEAN NOT NULL DEFAULT FALSE,
    map_url VARCHAR(2048)
);

CREATE TABLE IF NOT EXISTS delivery_jobs (
    event_key VARCHAR(512) NOT NULL REFERENCES delivery_events(event_key),
    chat_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'REJECTED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at BIGINT NOT NULL,
    finished_at BIGINT,
    PRIMARY KEY (event_key, chat_id)
);

CREATE INDEX IF NOT EXISTS delivery_jobs_due ON delivery_jobs(status, available_at);
