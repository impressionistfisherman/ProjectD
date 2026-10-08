BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.character_stat_investment_requests (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    connection_generation BIGINT NOT NULL CHECK (connection_generation > 0),
    request_id BIGINT NOT NULL CHECK (request_id > 0),
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id),
    stat_id SMALLINT NOT NULL CHECK (stat_id BETWEEN 0 AND 4),
    amount SMALLINT NOT NULL CHECK (amount > 0),
    level SMALLINT NOT NULL CHECK (level BETWEEN 1 AND 60),
    experience BIGINT NOT NULL CHECK (experience >= 0),
    unspent_stat_points SMALLINT NOT NULL CHECK (unspent_stat_points >= 0),
    strength SMALLINT NOT NULL CHECK (strength >= 0),
    dexterity SMALLINT NOT NULL CHECK (dexterity >= 0),
    arcana SMALLINT NOT NULL CHECK (arcana >= 0),
    vitality SMALLINT NOT NULL CHECK (vitality >= 0),
    spirit SMALLINT NOT NULL CHECK (spirit >= 0),
    free_reset_available BOOLEAN NOT NULL,
    PRIMARY KEY (account_id, connection_generation, request_id)
);

INSERT INTO dalmaru.schema_migrations (version, description)
VALUES (5, 'character stat investment requests')
ON CONFLICT (version) DO NOTHING;

COMMIT;
