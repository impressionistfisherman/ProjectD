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

INSERT INTO dalmaru.schema_migrations(version, description)
VALUES (49, 'home crop growth and one harvest per account generation')
ON CONFLICT (version) DO NOTHING;
