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
