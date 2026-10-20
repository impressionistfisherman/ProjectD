CREATE TABLE IF NOT EXISTS dalmaru.production_jobs (
    job_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id),
    housing_id UUID NOT NULL REFERENCES dalmaru.account_homes(housing_id) ON DELETE CASCADE,
    recipe_id TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 8),
    ready_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'CLAIMED', 'CANCELLED'))
);

CREATE TABLE IF NOT EXISTS dalmaru.production_materials (
    job_id UUID NOT NULL REFERENCES dalmaru.production_jobs(job_id) ON DELETE CASCADE,
    material_id TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (job_id, material_id)
);

CREATE TABLE dalmaru.market_stashes (
    stash_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id),
    material_id TEXT,
    equipment_id UUID,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    binding TEXT NOT NULL,
    location TEXT NOT NULL CHECK (location IN ('STASH', 'LISTED', 'SOLD', 'CLAIMED')),
    CHECK ((material_id IS NOT NULL AND equipment_id IS NULL) OR (material_id IS NULL AND equipment_id IS NOT NULL))
);

CREATE UNIQUE INDEX market_stash_equipment ON dalmaru.market_stashes(equipment_id) WHERE equipment_id IS NOT NULL AND location IN ('STASH', 'LISTED');

CREATE TABLE dalmaru.market_listings (
    listing_id UUID PRIMARY KEY,
    stash_id UUID NOT NULL UNIQUE REFERENCES dalmaru.market_stashes(stash_id),
    seller_account UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    seller_character UUID NOT NULL REFERENCES dalmaru.characters(character_id),
    channel TEXT NOT NULL CHECK (channel IN ('STALL', 'EXCHANGE')),
    price INTEGER NOT NULL CHECK (price > 0),
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'SOLD', 'CANCELLED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    fee INTEGER
);

INSERT INTO dalmaru.schema_migrations(version, description)
VALUES (50, 'production queue split from crops, stall listings and escrow')
ON CONFLICT (version) DO NOTHING;
