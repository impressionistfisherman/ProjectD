ALTER TABLE dalmaru.character_equipment ADD COLUMN refinement_grade SMALLINT NOT NULL DEFAULT 0 CHECK (refinement_grade BETWEEN 0 AND 5);
ALTER TABLE dalmaru.character_equipment ADD COLUMN refinement_failures SMALLINT NOT NULL DEFAULT 0 CHECK (refinement_failures >= 0);
ALTER TABLE dalmaru.character_equipment ADD COLUMN inheritance_credit INTEGER NOT NULL DEFAULT 0 CHECK (inheritance_credit >= 0);

CREATE TABLE dalmaru.character_refinement_requests (
    account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id) ON DELETE CASCADE,
    connection_generation BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    equipment_id UUID NOT NULL REFERENCES dalmaru.character_equipment(equipment_id) ON DELETE CASCADE,
    grade SMALLINT NOT NULL,
    failures SMALLINT NOT NULL,
    success BOOLEAN NOT NULL,
    PRIMARY KEY (account_id, connection_generation, request_id)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (16, 'equipment refinement') ON CONFLICT DO NOTHING;
