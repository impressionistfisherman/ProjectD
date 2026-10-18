BEGIN;

ALTER TABLE dalmaru.trade_offers
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE dalmaru.trade_offers
    DROP CONSTRAINT IF EXISTS trade_offers_version_positive;

ALTER TABLE dalmaru.trade_offers
    ADD CONSTRAINT trade_offers_version_positive CHECK (version >= 1);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (37, 'trade offer version')
ON CONFLICT DO NOTHING;

COMMIT;
