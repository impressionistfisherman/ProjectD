BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.character_deletions (
    character_id UUID PRIMARY KEY REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    deleted_at TIMESTAMPTZ NOT NULL
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (29, 'character deletions') ON CONFLICT DO NOTHING;

COMMIT;
