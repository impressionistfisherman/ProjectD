BEGIN;
ALTER TABLE dalmaru.run_instances ADD COLUMN IF NOT EXISTS content_snapshot JSONB;
-- V012 already stores pilot-1; preserve that label and unknown legacy snapshots.
ALTER TABLE dalmaru.run_instances DROP CONSTRAINT IF EXISTS run_content_snapshot_pair;
ALTER TABLE dalmaru.run_instances ADD CONSTRAINT run_content_snapshot_pair CHECK (
 content_snapshot IS NULL OR (content_version ~ '^[a-z0-9.-]{1,64}$' AND jsonb_typeof(content_snapshot)='object')
);
CREATE OR REPLACE FUNCTION dalmaru.preserve_run_content() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.content_id IS DISTINCT FROM NEW.content_id OR OLD.content_version IS DISTINCT FROM NEW.content_version OR OLD.content_snapshot IS DISTINCT FROM NEW.content_snapshot THEN
  RAISE EXCEPTION 'Run content snapshot is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS preserve_run_content ON dalmaru.run_instances;
CREATE TRIGGER preserve_run_content BEFORE UPDATE ON dalmaru.run_instances
 FOR EACH ROW EXECUTE FUNCTION dalmaru.preserve_run_content();
INSERT INTO dalmaru.schema_migrations(version,description) VALUES(26,'immutable run content version and snapshot') ON CONFLICT(version) DO NOTHING;
COMMIT;
