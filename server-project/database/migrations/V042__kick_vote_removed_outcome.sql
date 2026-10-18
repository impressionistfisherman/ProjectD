ALTER TABLE dalmaru.run_kick_votes DROP CONSTRAINT run_kick_votes_outcome_check;
ALTER TABLE dalmaru.run_kick_votes ADD CONSTRAINT run_kick_votes_outcome_check CHECK (outcome IS NULL OR outcome IN ('WAITING_REMOVAL', 'EXPIRED', 'REMOVED'));

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (42, 'kick vote removed outcome') ON CONFLICT DO NOTHING;
