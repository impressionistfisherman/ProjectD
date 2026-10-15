BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.operator_adjustments (
    request_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id) ON DELETE CASCADE,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    event_id UUID NOT NULL UNIQUE,
    kind TEXT NOT NULL CHECK (kind IN ('copper', 'material', 'skill_points')),
    item_id TEXT,
    delta INTEGER NOT NULL CHECK (delta <> 0 AND delta BETWEEN -1000000 AND 1000000),
    reason TEXT NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 200),
    result_balance INTEGER NOT NULL CHECK (result_balance >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((kind IN ('copper', 'skill_points') AND item_id IS NULL) OR (kind = 'material' AND item_id ~ '^[a-z][a-z0-9._]{0,63}$'))
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (27, 'operator adjustment ledger') ON CONFLICT DO NOTHING;

COMMIT;
