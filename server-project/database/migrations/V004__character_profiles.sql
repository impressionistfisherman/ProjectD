BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.character_profiles (
    character_id UUID PRIMARY KEY REFERENCES dalmaru.characters(character_id),
    level SMALLINT NOT NULL DEFAULT 1 CHECK (level BETWEEN 1 AND 60),
    experience BIGINT NOT NULL DEFAULT 0 CHECK (experience >= 0),
    unspent_stat_points SMALLINT NOT NULL DEFAULT 0 CHECK (unspent_stat_points >= 0),
    strength SMALLINT NOT NULL DEFAULT 0 CHECK (strength >= 0),
    dexterity SMALLINT NOT NULL DEFAULT 0 CHECK (dexterity >= 0),
    arcana SMALLINT NOT NULL DEFAULT 0 CHECK (arcana >= 0),
    vitality SMALLINT NOT NULL DEFAULT 0 CHECK (vitality >= 0),
    spirit SMALLINT NOT NULL DEFAULT 0 CHECK (spirit >= 0),
    free_reset_available BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO dalmaru.character_profiles (character_id)
SELECT character_id FROM dalmaru.characters
ON CONFLICT (character_id) DO NOTHING;

INSERT INTO dalmaru.schema_migrations (version, description)
VALUES (4, 'character starting profiles')
ON CONFLICT (version) DO NOTHING;

COMMIT;
