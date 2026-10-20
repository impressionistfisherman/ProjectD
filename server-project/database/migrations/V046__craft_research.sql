CREATE TABLE IF NOT EXISTS dalmaru.character_learned_recipes (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    recipe_id TEXT NOT NULL,
    PRIMARY KEY (character_id, recipe_id)
);

CREATE TABLE IF NOT EXISTS dalmaru.character_craft_research (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    clue_id TEXT NOT NULL,
    candidates TEXT NOT NULL,
    PRIMARY KEY (character_id, clue_id)
);

CREATE TABLE IF NOT EXISTS dalmaru.character_precision_orders (
    order_id UUID PRIMARY KEY,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    recipe_id TEXT NOT NULL,
    equipment_id UUID,
    step_index INTEGER NOT NULL DEFAULT 0 CHECK (step_index >= 0),
    choice TEXT,
    succeeded BOOLEAN,
    state TEXT NOT NULL DEFAULT 'OPEN'
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (46, 'craft research') ON CONFLICT DO NOTHING;
