BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.character_supply_grants (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    grant_key TEXT NOT NULL CHECK (grant_key ~ '^[a-z][a-z0-9_]{0,31}$'),
    PRIMARY KEY (character_id, grant_key)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (28, 'character supply grants') ON CONFLICT DO NOTHING;

COMMIT;
