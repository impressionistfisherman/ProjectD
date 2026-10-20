CREATE TABLE dalmaru.home_crops (
    housing_id UUID NOT NULL REFERENCES dalmaru.account_homes(housing_id) ON DELETE CASCADE,
    plot SMALLINT NOT NULL CHECK (plot BETWEEN 0 AND 3),
    crop TEXT NOT NULL CHECK (crop IN ('grain', 'vegetable', 'herb', 'fiber', 'flower')),
    generation INTEGER NOT NULL CHECK (generation > 0),
    planted_at TIMESTAMPTZ NOT NULL,
    ready_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (housing_id, plot)
);

CREATE TABLE dalmaru.home_crop_harvests (
    housing_id UUID NOT NULL REFERENCES dalmaru.account_homes(housing_id) ON DELETE CASCADE,
    plot SMALLINT NOT NULL CHECK (plot BETWEEN 0 AND 3),
    generation INTEGER NOT NULL CHECK (generation > 0),
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    PRIMARY KEY (housing_id, plot, generation, account_id)
);

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

INSERT INTO dalmaru.schema_migrations(version, description)
VALUES (49, 'home crop growth and one harvest per account generation')
ON CONFLICT (version) DO NOTHING;
