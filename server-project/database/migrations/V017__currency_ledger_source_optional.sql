ALTER TABLE dalmaru.character_currency_ledger ALTER COLUMN source_run_id DROP NOT NULL;
INSERT INTO dalmaru.schema_migrations (version, description) VALUES (17, 'currency ledger optional source') ON CONFLICT DO NOTHING;
