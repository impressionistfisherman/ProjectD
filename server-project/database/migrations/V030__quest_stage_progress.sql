BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.quest_stage_progress (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    quest_id TEXT NOT NULL,
    stage TEXT NOT NULL,
    confirmed BOOLEAN NOT NULL,
    PRIMARY KEY (character_id, quest_id, stage)
);

CREATE INDEX IF NOT EXISTS quest_stage_progress_stage_idx ON dalmaru.quest_stage_progress (quest_id, stage);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (30, 'quest stage progress') ON CONFLICT DO NOTHING;

COMMIT;
