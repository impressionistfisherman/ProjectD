BEGIN;
CREATE TABLE IF NOT EXISTS dalmaru.character_waypoints (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    waypoint_id TEXT NOT NULL CHECK(char_length(waypoint_id) BETWEEN 1 AND 128),
    unlocked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cooldown_until TIMESTAMPTZ(3),
    PRIMARY KEY(character_id,waypoint_id)
);
INSERT INTO dalmaru.schema_migrations(version,description) VALUES(31,'first-visit waypoints and persistent return cooldown') ON CONFLICT DO NOTHING;
COMMIT;
