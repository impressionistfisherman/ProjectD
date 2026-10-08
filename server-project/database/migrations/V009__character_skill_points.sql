ALTER TABLE dalmaru.character_profiles
    ADD COLUMN unspent_skill_points SMALLINT NOT NULL DEFAULT 0 CHECK (unspent_skill_points >= 0);

INSERT INTO dalmaru.schema_migrations (version, description)
VALUES (9, 'character profession skill points')
ON CONFLICT (version) DO NOTHING;
