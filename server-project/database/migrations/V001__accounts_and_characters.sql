BEGIN;

CREATE SCHEMA IF NOT EXISTS dalmaru;

CREATE TABLE IF NOT EXISTS dalmaru.schema_migrations (
    version INTEGER PRIMARY KEY,
    description TEXT NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dalmaru.accounts (
    account_id UUID PRIMARY KEY,
    active_character_id UUID,
    connection_generation BIGINT NOT NULL DEFAULT 0 CHECK (connection_generation >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dalmaru.characters (
    character_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    slot SMALLINT NOT NULL CHECK (slot BETWEEN 1 AND 4),
    display_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (account_id, slot),
    UNIQUE (normalized_name)
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'accounts_active_character_fk'
          AND connamespace = 'dalmaru'::regnamespace
    ) THEN
        ALTER TABLE dalmaru.accounts
            ADD CONSTRAINT accounts_active_character_fk
            FOREIGN KEY (active_character_id)
            REFERENCES dalmaru.characters(character_id);
    END IF;
END $$;

INSERT INTO dalmaru.schema_migrations (version, description)
VALUES (1, 'accounts and characters')
ON CONFLICT (version) DO NOTHING;

COMMIT;
