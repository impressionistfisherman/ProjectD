BEGIN;

ALTER TABLE dalmaru.character_mailbox ALTER COLUMN quantity TYPE INTEGER;
ALTER TABLE dalmaru.character_mailbox DROP CONSTRAINT character_mailbox_source_run_id_character_id_item_id_key;
-- event_id already exists as the primary key; keep it for legacy and S13 readers.
ALTER TABLE dalmaru.character_mailbox ADD UNIQUE(source_run_id, character_id, item_id, event_id);

CREATE TABLE dalmaru.run_auctions (
    auction_id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES dalmaru.run_instances(run_id) ON DELETE CASCADE,
    terms JSONB NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('OPEN','DIRECT','WON','UNSOLD','EMPTY')),
    started_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    maximum_end_at TIMESTAMPTZ NOT NULL,
    highest_character_id UUID REFERENCES dalmaru.characters(character_id) ON DELETE SET NULL,
    highest_bid INTEGER NOT NULL DEFAULT 0 CHECK (highest_bid >= 0),
    settled_at TIMESTAMPTZ,
    CHECK (started_at < ends_at AND ends_at <= maximum_end_at),
    CHECK (state <> 'OPEN' OR (highest_character_id IS NULL) = (highest_bid = 0)),
    CHECK ((state = 'OPEN') = (settled_at IS NULL))
);
CREATE INDEX run_auctions_expiry ON dalmaru.run_auctions(ends_at) WHERE state='OPEN';

CREATE TABLE dalmaru.run_auction_members (
    auction_id UUID NOT NULL REFERENCES dalmaru.run_auctions(auction_id) ON DELETE CASCADE,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (auction_id, character_id)
);

INSERT INTO dalmaru.schema_migrations(version,description) VALUES (38,'run auctions and integer mailbox quantity');
COMMIT;
