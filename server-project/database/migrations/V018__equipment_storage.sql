ALTER TABLE dalmaru.character_equipment DROP CONSTRAINT character_equipment_character_id_slot_id_key;
ALTER TABLE dalmaru.character_equipment DROP CONSTRAINT character_equipment_slot_id_check;
ALTER TABLE dalmaru.character_equipment ALTER COLUMN slot_id DROP NOT NULL;
ALTER TABLE dalmaru.character_equipment ADD COLUMN location TEXT NOT NULL DEFAULT 'EQUIPPED' CHECK (location IN ('EQUIPPED', 'INVENTORY'));
ALTER TABLE dalmaru.character_equipment ADD CONSTRAINT character_equipment_location_slot_check CHECK ((location='EQUIPPED' AND slot_id BETWEEN 0 AND 8) OR (location='INVENTORY' AND slot_id IS NULL));
CREATE UNIQUE INDEX character_equipment_equipped_slot ON dalmaru.character_equipment (character_id, slot_id) WHERE location='EQUIPPED';

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (18, 'equipment storage') ON CONFLICT DO NOTHING;
