BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.character_creation_requests (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    connection_generation BIGINT NOT NULL CHECK (connection_generation >= 0),
    request_id BIGINT NOT NULL CHECK (request_id > 0),
    slot SMALLINT NOT NULL CHECK (slot BETWEEN 1 AND 4),
    display_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    character_id UUID NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, connection_generation, request_id)
);

INSERT INTO dalmaru.schema_migrations (version, description)
VALUES (3, 'character creation requests')
ON CONFLICT (version) DO NOTHING;

COMMIT;
