CREATE TABLE dalmaru.run_contributions (
    run_id UUID NOT NULL,
    character_id UUID NOT NULL,
    action TEXT CHECK (action IN ('EFFECT', 'RECEIVED_HIT', 'MECHANIC')),
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, character_id),
    FOREIGN KEY (run_id, character_id) REFERENCES dalmaru.run_participants(run_id, character_id) ON DELETE CASCADE
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (35, 'run contributions') ON CONFLICT DO NOTHING;
