BEGIN;

CREATE TABLE IF NOT EXISTS dalmaru.trade_offers (
    offer_id UUID PRIMARY KEY,
    opener_account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    partner_account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    state TEXT NOT NULL CHECK (state IN ('reserved', 'completed', 'cancelled', 'expired')),
    expires_at TIMESTAMPTZ NOT NULL,
    opener_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    partner_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (opener_account_id <> partner_account_id)
);

CREATE TABLE IF NOT EXISTS dalmaru.trade_lines (
    offer_id UUID NOT NULL REFERENCES dalmaru.trade_offers(offer_id) ON DELETE CASCADE,
    account_id UUID NOT NULL,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id),
    item_id TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    binding TEXT NOT NULL CHECK (binding IN ('FREE', 'ACCOUNT')),
    PRIMARY KEY (offer_id, account_id)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (33, 'direct trade offers') ON CONFLICT DO NOTHING;

COMMIT;
