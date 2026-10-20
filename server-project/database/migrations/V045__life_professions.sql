CREATE TABLE IF NOT EXISTS dalmaru.character_life_professions (
    character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id) ON DELETE CASCADE,
    job TEXT NOT NULL CHECK (job IN ('blacksmith', 'bowyer', 'carpenter', 'tailor', 'lapidary', 'cook', 'alchemist', 'apothecary', 'charm_maker')),
    proficiency INTEGER NOT NULL CHECK (proficiency BETWEEN 1 AND 100),
    specialization TEXT,
    renown INTEGER NOT NULL DEFAULT 0 CHECK (renown >= 0),
    task_state TEXT,
    first_specialization_used BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (character_id, job)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (45, 'life professions') ON CONFLICT DO NOTHING;
