CREATE TABLE dalmaru.run_party_control (
    run_id UUID PRIMARY KEY REFERENCES dalmaru.run_instances(run_id) ON DELETE CASCADE,
    roster_revision BIGINT NOT NULL DEFAULT 1 CHECK (roster_revision > 0),
    stage_revision BIGINT NOT NULL DEFAULT 1 CHECK (stage_revision > 0),
    rules_snapshot JSONB NOT NULL,
    leader_character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id),
    phase TEXT NOT NULL CHECK (phase IN ('READY', 'ACTIVE', 'PAUSED', 'COMPLETED', 'FAILED'))
);

CREATE TABLE dalmaru.run_member_lifecycle (
    run_id UUID NOT NULL,
    character_id UUID NOT NULL,
    leave_reason TEXT NULL CHECK (leave_reason IS NULL OR leave_reason IN ('VOLUNTARY', 'KICKED', 'RECONNECT_EXPIRED')),
    left_at TIMESTAMPTZ NULL,
    disconnect_at TIMESTAMPTZ NULL,
    eligibility_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    return_pending BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (run_id, character_id),
    FOREIGN KEY (run_id, character_id) REFERENCES dalmaru.run_participants(run_id, character_id) ON DELETE CASCADE,
    CHECK ((left_at IS NULL AND leave_reason IS NULL) OR (left_at IS NOT NULL AND leave_reason IS NOT NULL))
);

CREATE TABLE dalmaru.run_party_invites (
    run_id UUID NOT NULL REFERENCES dalmaru.run_party_control(run_id) ON DELETE CASCADE,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    roster_revision BIGINT NOT NULL CHECK (roster_revision > 0),
    invited_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, character_id)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (40, 'run member lifecycle') ON CONFLICT DO NOTHING;
