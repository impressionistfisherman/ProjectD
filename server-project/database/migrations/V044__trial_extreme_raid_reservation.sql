ALTER TABLE dalmaru.run_instances DROP CONSTRAINT IF EXISTS run_instances_difficulty_check;
ALTER TABLE dalmaru.run_instances ADD CONSTRAINT run_instances_difficulty_check CHECK (difficulty IN ('FIRST_STEP', 'TRAINING', 'TRIAL', 'EXTREME'));

ALTER TABLE dalmaru.character_run_first_rewards DROP CONSTRAINT IF EXISTS character_run_first_rewards_difficulty_check;
ALTER TABLE dalmaru.character_run_first_rewards ADD CONSTRAINT character_run_first_rewards_difficulty_check CHECK (difficulty IN ('FIRST_STEP', 'TRAINING', 'TRIAL', 'EXTREME'));

ALTER TABLE dalmaru.run_instances ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'DUNGEON';
ALTER TABLE dalmaru.run_instances DROP CONSTRAINT IF EXISTS run_instances_kind_check;
ALTER TABLE dalmaru.run_instances ADD CONSTRAINT run_instances_kind_check CHECK (kind IN ('DUNGEON', 'RAID'));

ALTER TABLE dalmaru.run_instances DROP CONSTRAINT IF EXISTS run_instances_party_size_check;
ALTER TABLE dalmaru.run_instances DROP CONSTRAINT IF EXISTS run_instances_party_kind_check;
ALTER TABLE dalmaru.run_instances ADD CONSTRAINT run_instances_party_kind_check CHECK ((kind = 'DUNGEON' AND party_size BETWEEN 1 AND 4) OR (kind = 'RAID' AND party_size BETWEEN 1 AND 8));

ALTER TABLE dalmaru.run_instances DROP CONSTRAINT IF EXISTS run_instances_raid_closed;
ALTER TABLE dalmaru.run_instances ADD CONSTRAINT run_instances_raid_closed CHECK (kind <> 'RAID');

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (44, 'trial extreme and closed raid reservation') ON CONFLICT DO NOTHING;
