CREATE TABLE dalmaru.character_skill_ranks (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    skill_id TEXT NOT NULL,
    rank SMALLINT NOT NULL CHECK (rank BETWEEN 1 AND 5),
    PRIMARY KEY (character_id, skill_id)
);

CREATE TABLE dalmaru.character_skill_learning_requests (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id) ON DELETE CASCADE,
    connection_generation BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    skill_id TEXT NOT NULL,
    rank SMALLINT NOT NULL CHECK (rank BETWEEN 1 AND 5),
    PRIMARY KEY (account_id, connection_generation, request_id)
);

INSERT INTO dalmaru.schema_migrations (version, description)
VALUES (10, 'character skill ranks')
ON CONFLICT (version) DO NOTHING;
