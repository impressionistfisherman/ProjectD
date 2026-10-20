CREATE TABLE dalmaru.character_advancements (
    character_id UUID PRIMARY KEY REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    branch TEXT CHECK (branch IN ('warrior.twohand', 'warrior.shield', 'archer.bow', 'archer.crossbow',
                                 'mage.fan', 'mage.ritual', 'rogue.bandit', 'rogue.pirate')),
    advanced_30_at TIMESTAMPTZ,
    advanced_50_at TIMESTAMPTZ,
    advanced_60_at TIMESTAMPTZ,
    rogue_reset_used BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK ((branch IS NOT NULL) = (advanced_30_at IS NOT NULL)),
    CHECK (advanced_50_at IS NULL OR advanced_30_at IS NOT NULL),
    CHECK (advanced_60_at IS NULL OR advanced_50_at IS NOT NULL),
    CHECK (NOT rogue_reset_used OR (advanced_30_at IS NOT NULL AND branch IN ('rogue.bandit', 'rogue.pirate')))
);

INSERT INTO dalmaru.schema_migrations(version, description)
VALUES (43, 'character advancement milestones and rogue redistribution')
ON CONFLICT (version) DO NOTHING;
