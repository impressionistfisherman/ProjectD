BEGIN;
CREATE TABLE dalmaru.character_skill_loadouts (
 character_id UUID PRIMARY KEY REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
 revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
 slots JSONB NOT NULL CHECK(jsonb_typeof(slots)='array' AND jsonb_array_length(slots)=5)
);
CREATE TABLE dalmaru.skill_loadout_requests (
 account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id), connection_generation BIGINT NOT NULL,
 request_id BIGINT NOT NULL, character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id),
 revision BIGINT NOT NULL, slots JSONB NOT NULL,
 PRIMARY KEY(account_id,connection_generation,request_id)
);
ALTER TABLE dalmaru.run_participants ADD COLUMN frozen_loadout JSONB;
ALTER TABLE dalmaru.run_participants ADD COLUMN loadout_revision BIGINT CHECK(loadout_revision>=0);
INSERT INTO dalmaru.schema_migrations(version,description) VALUES(23,'server owned combat loadouts');
COMMIT;
