BEGIN;
ALTER TABLE dalmaru.character_equipment ADD COLUMN binding TEXT NOT NULL DEFAULT 'FREE' CHECK (binding IN ('FREE','ACCOUNT','CHARACTER'));
UPDATE dalmaru.character_equipment SET binding='CHARACTER',bound=TRUE WHERE bound OR location='EQUIPPED';
ALTER TABLE dalmaru.character_materials ADD COLUMN binding TEXT NOT NULL DEFAULT 'FREE' CHECK (binding IN ('FREE','ACCOUNT','CHARACTER'));
CREATE TABLE dalmaru.character_equipment_move_requests (
 account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id), connection_generation BIGINT NOT NULL,
 request_id BIGINT NOT NULL, equipment_id UUID NOT NULL, slot_id SMALLINT NOT NULL,
 PRIMARY KEY(account_id,connection_generation,request_id)
);
CREATE TABLE dalmaru.character_vanilla_state (
 character_id UUID PRIMARY KEY REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
 state JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Town boundaries are explicit content data; an unregistered location is not a safe switching area.
CREATE TABLE dalmaru.town_safe_zones (
 name TEXT PRIMARY KEY, dimension TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
 radius INTEGER NOT NULL CHECK(radius BETWEEN 1 AND 128)
);
ALTER TABLE dalmaru.run_participants ADD COLUMN return_dimension TEXT;
ALTER TABLE dalmaru.run_participants ADD COLUMN return_x DOUBLE PRECISION;
ALTER TABLE dalmaru.run_participants ADD COLUMN return_y DOUBLE PRECISION;
ALTER TABLE dalmaru.run_participants ADD COLUMN return_z DOUBLE PRECISION;
ALTER TABLE dalmaru.run_participants ADD COLUMN return_pending BOOLEAN NOT NULL DEFAULT FALSE;
INSERT INTO dalmaru.schema_migrations(version,description) VALUES(21,'premerge ownership and character safety') ON CONFLICT DO NOTHING;
COMMIT;
