ALTER TABLE dalmaru.character_equipment ADD CONSTRAINT character_equipment_character_id_slot_id_key UNIQUE (character_id, slot_id);
INSERT INTO dalmaru.schema_migrations (version, description) VALUES (20, 'equipment slot conflict compatibility') ON CONFLICT DO NOTHING;
