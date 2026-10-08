CREATE TABLE dalmaru.character_currency_ledger (
    event_id UUID PRIMARY KEY,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    source_run_id UUID NOT NULL REFERENCES dalmaru.run_instances(run_id) ON DELETE CASCADE,
    reason TEXT NOT NULL,
    copper_delta INTEGER NOT NULL,
    UNIQUE (source_run_id, character_id, reason)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (14, 'currency ledger') ON CONFLICT DO NOTHING;
