BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.account_holdings (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id) ON DELETE CASCADE,
    asset_id TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    binding TEXT NOT NULL CHECK (binding IN ('FREE', 'ACCOUNT')),
    PRIMARY KEY (account_id, asset_id)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (32, 'account holdings') ON CONFLICT DO NOTHING;

COMMIT;
