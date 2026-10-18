CREATE TABLE dalmaru.run_kick_votes (
    vote_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES dalmaru.run_party_control(run_id) ON DELETE CASCADE,
    target_character_id UUID NOT NULL,
    opener_character_id UUID NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ NULL,
    outcome TEXT NULL CHECK (outcome IS NULL OR outcome IN ('WAITING_REMOVAL', 'EXPIRED')),
    roster_revision BIGINT NOT NULL CHECK (roster_revision > 0),
    FOREIGN KEY (run_id, target_character_id) REFERENCES dalmaru.run_member_lifecycle(run_id, character_id),
    FOREIGN KEY (run_id, opener_character_id) REFERENCES dalmaru.run_member_lifecycle(run_id, character_id),
    CHECK (opener_character_id <> target_character_id),
    CHECK ((closed_at IS NULL AND outcome IS NULL) OR (closed_at IS NOT NULL AND outcome IS NOT NULL))
);

CREATE UNIQUE INDEX run_kick_votes_one_open ON dalmaru.run_kick_votes (run_id) WHERE closed_at IS NULL;

CREATE TABLE dalmaru.run_kick_electorate (
    vote_id UUID NOT NULL REFERENCES dalmaru.run_kick_votes(vote_id) ON DELETE CASCADE,
    character_id UUID NOT NULL,
    PRIMARY KEY (vote_id, character_id)
);

CREATE TABLE dalmaru.run_kick_ballots (
    vote_id UUID NOT NULL REFERENCES dalmaru.run_kick_votes(vote_id) ON DELETE CASCADE,
    voter_character_id UUID NOT NULL,
    choice TEXT NOT NULL CHECK (choice IN ('YES', 'NO')),
    cast_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (vote_id, voter_character_id),
    FOREIGN KEY (vote_id, voter_character_id) REFERENCES dalmaru.run_kick_electorate(vote_id, character_id)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (41, 'run kick votes') ON CONFLICT DO NOTHING;
