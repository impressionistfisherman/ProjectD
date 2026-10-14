BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.operation_requests (
    request_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id) ON DELETE CASCADE,
    connection_generation BIGINT NOT NULL CHECK(connection_generation>0),
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    operation TEXT NOT NULL,
    legacy_request_id BIGINT NOT NULL CHECK(legacy_request_id>0),
    payload_hash TEXT NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    event_id UUID NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK(state IN('accepted','pending_recovery','completed')),
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at TIMESTAMPTZ,
    CHECK((state='accepted' AND result IS NULL AND applied_at IS NULL) OR
          (state='pending_recovery' AND result IS NOT NULL AND applied_at IS NULL) OR
          (state='completed' AND result IS NOT NULL AND applied_at IS NOT NULL)),
    UNIQUE(account_id,connection_generation,operation,legacy_request_id)
);
CREATE INDEX IF NOT EXISTS operation_requests_recovery ON dalmaru.operation_requests(account_id,character_id) WHERE state='pending_recovery';

INSERT INTO dalmaru.schema_migrations(version,description) VALUES(25,'UUID operation requests and application receipts') ON CONFLICT DO NOTHING;
COMMIT;
