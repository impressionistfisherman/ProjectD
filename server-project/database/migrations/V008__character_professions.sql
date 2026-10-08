CREATE TABLE dalmaru.character_professions (
    character_id UUID PRIMARY KEY REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    base_job TEXT NOT NULL CHECK (base_job IN ('WARRIOR', 'ARCHER', 'MAGE', 'ROGUE')),
    selected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE dalmaru.character_profession_requests (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id) ON DELETE CASCADE,
    connection_generation BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    base_job TEXT NOT NULL CHECK (base_job IN ('WARRIOR', 'ARCHER', 'MAGE', 'ROGUE')),
    PRIMARY KEY (account_id, connection_generation, request_id)
);
