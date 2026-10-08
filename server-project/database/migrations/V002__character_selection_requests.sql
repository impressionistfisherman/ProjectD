BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.character_selection_requests (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    connection_generation BIGINT NOT NULL CHECK (connection_generation >= 0),
    request_id BIGINT NOT NULL CHECK (request_id > 0),
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, connection_generation, request_id)
);

INSERT INTO dalmaru.schema_migrations (version, description)
VALUES (2, 'character selection requests')
ON CONFLICT (version) DO NOTHING;

COMMIT;
