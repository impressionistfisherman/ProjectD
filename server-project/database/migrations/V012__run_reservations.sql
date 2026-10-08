CREATE TABLE dalmaru.run_instances (
    run_id UUID PRIMARY KEY,
    content_id TEXT NOT NULL,
    difficulty TEXT NOT NULL CHECK (difficulty IN ('FIRST_STEP', 'TRAINING')),
    content_version TEXT NOT NULL DEFAULT 'pilot-1',
    week_id DATE NOT NULL,
    party_size SMALLINT NOT NULL CHECK (party_size BETWEEN 1 AND 4),
    scaling_tier SMALLINT NOT NULL CHECK (scaling_tier IN (1, 2, 4)),
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'COMPLETED', 'FAILED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);

CREATE TABLE dalmaru.run_participants (
    run_id UUID NOT NULL REFERENCES dalmaru.run_instances(run_id) ON DELETE CASCADE,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    PRIMARY KEY (run_id, character_id)
);

CREATE TABLE dalmaru.character_run_weekly_clears (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    content_id TEXT NOT NULL,
    difficulty TEXT NOT NULL,
    week_id DATE NOT NULL,
    clear_count SMALLINT NOT NULL DEFAULT 0 CHECK (clear_count BETWEEN 0 AND 3),
    PRIMARY KEY (character_id, content_id, difficulty, week_id)
);

CREATE TABLE dalmaru.run_requests (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id) ON DELETE CASCADE,
    connection_generation BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('ENTER', 'COMPLETE', 'FAIL')),
    run_id UUID NOT NULL REFERENCES dalmaru.run_instances(run_id) ON DELETE CASCADE,
    state TEXT NOT NULL,
    PRIMARY KEY (account_id, connection_generation, request_id)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (12, 'run reservations') ON CONFLICT DO NOTHING;
