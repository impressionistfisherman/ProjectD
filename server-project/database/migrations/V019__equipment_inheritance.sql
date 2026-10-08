CREATE TABLE dalmaru.character_inheritance_requests (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id) ON DELETE CASCADE,
    connection_generation BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    target_equipment_id UUID NOT NULL REFERENCES dalmaru.character_equipment(equipment_id) ON DELETE CASCADE,
    source_equipment_id UUID NOT NULL,
    credit INTEGER NOT NULL,
    PRIMARY KEY (account_id, connection_generation, request_id)
);
INSERT INTO dalmaru.schema_migrations (version, description) VALUES (19, 'equipment inheritance') ON CONFLICT DO NOTHING;
