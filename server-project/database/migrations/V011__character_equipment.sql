CREATE TABLE dalmaru.character_equipment (
    equipment_id UUID PRIMARY KEY,
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    slot_id SMALLINT NOT NULL CHECK (slot_id BETWEEN 0 AND 8),
    item_id TEXT NOT NULL,
    required_level SMALLINT NOT NULL DEFAULT 1 CHECK (required_level BETWEEN 1 AND 60),
    profession TEXT,
    enhancement SMALLINT NOT NULL DEFAULT 0 CHECK (enhancement BETWEEN 0 AND 15),
    consecutive_failures SMALLINT NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    bound BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (character_id, slot_id)
);

CREATE TABLE dalmaru.character_enhancement_requests (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id) ON DELETE CASCADE,
    connection_generation BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    equipment_id UUID NOT NULL REFERENCES dalmaru.character_equipment(equipment_id) ON DELETE CASCADE,
    enhancement SMALLINT NOT NULL,
    consecutive_failures SMALLINT NOT NULL,
    success BOOLEAN NOT NULL,
    PRIMARY KEY (account_id, connection_generation, request_id)
);

INSERT INTO dalmaru.schema_migrations (version, description)
VALUES (11, 'character equipment and enhancement')
ON CONFLICT (version) DO NOTHING;
