CREATE TABLE dalmaru.character_wallet (
    character_id UUID PRIMARY KEY REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    copper INTEGER NOT NULL DEFAULT 0 CHECK (copper >= 0)
);

CREATE TABLE dalmaru.character_materials (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    material_id TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    PRIMARY KEY (character_id, material_id)
);

CREATE TABLE dalmaru.character_mailbox (
    event_id UUID PRIMARY KEY,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    source_run_id UUID NOT NULL REFERENCES dalmaru.run_instances(run_id) ON DELETE CASCADE,
    item_id TEXT NOT NULL,
    quantity SMALLINT NOT NULL CHECK (quantity > 0),
    claimed_at TIMESTAMPTZ,
    UNIQUE (source_run_id, character_id, item_id)
);

CREATE TABLE dalmaru.run_reward_grants (
    run_id UUID NOT NULL REFERENCES dalmaru.run_instances(run_id) ON DELETE CASCADE,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    copper INTEGER NOT NULL CHECK (copper >= 0),
    ironstone INTEGER NOT NULL CHECK (ironstone >= 0),
    beacon_token INTEGER NOT NULL CHECK (beacon_token >= 0),
    equipment_box TEXT,
    PRIMARY KEY (run_id, character_id)
);

CREATE TABLE dalmaru.character_run_first_rewards (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    difficulty TEXT NOT NULL CHECK (difficulty IN ('FIRST_STEP', 'TRAINING')),
    PRIMARY KEY (character_id, difficulty)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (13, 'run rewards') ON CONFLICT DO NOTHING;
