BEGIN;
ALTER TABLE dalmaru.character_profiles ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0);
CREATE FUNCTION dalmaru.bump_profile_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.revision := OLD.revision + 1; RETURN NEW; END $$;
CREATE TRIGGER profile_revision BEFORE UPDATE ON dalmaru.character_profiles FOR EACH ROW EXECUTE FUNCTION dalmaru.bump_profile_revision();
ALTER TABLE dalmaru.accounts ADD COLUMN vanilla_owner TEXT;
ALTER TABLE dalmaru.accounts ADD COLUMN vanilla_checkpoint JSONB;
ALTER TABLE dalmaru.run_participants ADD COLUMN return_mode TEXT CHECK(return_mode IN ('survival','creative','adventure','spectator'));
INSERT INTO dalmaru.schema_migrations(version,description) VALUES(22,'review profile order and crash recovery');
COMMIT;
