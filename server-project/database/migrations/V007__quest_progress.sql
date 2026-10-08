CREATE TABLE dalmaru.character_quest_progress (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    quest_id TEXT NOT NULL,
    content_version TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('AVAILABLE', 'ACTIVE', 'COMPLETED', 'REWARDED')),
    progress_count INTEGER NOT NULL DEFAULT 0 CHECK (progress_count >= 0),
    claimed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id, quest_id, content_version)
);

CREATE INDEX character_quest_progress_active_idx
    ON dalmaru.character_quest_progress (character_id, state);
