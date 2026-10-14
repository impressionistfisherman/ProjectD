BEGIN;
CREATE TABLE dalmaru.character_safe_points (
 character_id UUID PRIMARY KEY REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
 point_id TEXT NOT NULL, dimension TEXT NOT NULL,x DOUBLE PRECISION NOT NULL,y DOUBLE PRECISION NOT NULL,z DOUBLE PRECISION NOT NULL,
 activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO dalmaru.schema_migrations(version,description) VALUES(24,'character activated safety checkpoint');
COMMIT;
