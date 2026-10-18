BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.account_gather_cooldowns (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id) ON DELETE CASCADE,
    node_id TEXT NOT NULL,
    ready_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, node_id)
);

CREATE TABLE IF NOT EXISTS dalmaru.character_life_xp (
    character_id UUID PRIMARY KEY REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    xp INTEGER NOT NULL CHECK (xp >= 0)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (34, 'gather cooldowns and life xp') ON CONFLICT DO NOTHING;

COMMIT;
