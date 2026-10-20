CREATE TABLE dalmaru.account_homes (
    account_id UUID PRIMARY KEY REFERENCES dalmaru.accounts(account_id),
    housing_id UUID NOT NULL UNIQUE,
    dimension TEXT NOT NULL DEFAULT 'dalmaru:housing',
    parcel INTEGER,
    built BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE UNIQUE INDEX IF NOT EXISTS account_homes_parcel ON dalmaru.account_homes(parcel) WHERE parcel IS NOT NULL;

CREATE TABLE dalmaru.account_home_spaces (
    housing_id UUID NOT NULL REFERENCES dalmaru.account_homes(housing_id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('HOUSE', 'PLOT', 'WORKSHOP')),
    plot_index SMALLINT NOT NULL,
    origin_x INTEGER NOT NULL,
    origin_y INTEGER NOT NULL,
    origin_z INTEGER NOT NULL,
    size INTEGER NOT NULL CHECK (size > 0),
    PRIMARY KEY (housing_id, kind, plot_index),
    CHECK ((kind = 'PLOT' AND plot_index BETWEEN 0 AND 3) OR (kind <> 'PLOT' AND plot_index = -1))
);

CREATE TABLE dalmaru.account_home_presence (
    character_id UUID PRIMARY KEY REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    housing_id UUID NOT NULL REFERENCES dalmaru.account_homes(housing_id),
    inside BOOLEAN NOT NULL,
    return_dimension TEXT NOT NULL,
    return_x INTEGER NOT NULL,
    return_y INTEGER NOT NULL,
    return_z INTEGER NOT NULL
);

CREATE TABLE dalmaru.account_home_grants (
    grant_id UUID PRIMARY KEY,
    housing_id UUID NOT NULL REFERENCES dalmaru.account_homes(housing_id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    permission TEXT NOT NULL CHECK (permission IN ('VISIT', 'INVITE', 'COLLAB')),
    target_id TEXT,
    revoked_at TIMESTAMPTZ
);

INSERT INTO dalmaru.schema_migrations(version, description)
VALUES (48, 'account homes, spaces, and reserved grants')
ON CONFLICT (version) DO NOTHING;
