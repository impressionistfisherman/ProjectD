ALTER TABLE dalmaru.run_instances DROP CONSTRAINT run_instances_state_check;
ALTER TABLE dalmaru.run_instances ADD CONSTRAINT run_instances_state_check CHECK (state IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'FAILED'));
ALTER TABLE dalmaru.run_instances ADD COLUMN deadline_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '45 minutes');
ALTER TABLE dalmaru.run_instances ADD COLUMN paused_at TIMESTAMPTZ;
ALTER TABLE dalmaru.run_instances ADD COLUMN pause_expires_at TIMESTAMPTZ;

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (15, 'run lifecycle') ON CONFLICT DO NOTHING;
