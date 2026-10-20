CREATE TABLE IF NOT EXISTS dalmaru.character_craft_orders (
    order_id UUID PRIMARY KEY,
    requester_account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id),
    requester_character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id),
    crafter_account_id UUID REFERENCES dalmaru.accounts(account_id),
    crafter_character_id UUID REFERENCES dalmaru.characters(character_id),
    recipe_id TEXT NOT NULL,
    copper INTEGER NOT NULL CHECK (copper >= 0),
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'ACCEPTED', 'SETTLED', 'CANCELLED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    equipment_id UUID,
    binding TEXT CHECK (binding IS NULL OR binding IN ('FREE', 'ACCOUNT', 'CHARACTER')),
    fee INTEGER CHECK (fee IS NULL OR fee >= 0)
);

CREATE TABLE IF NOT EXISTS dalmaru.character_craft_order_materials (
    order_id UUID NOT NULL REFERENCES dalmaru.character_craft_orders(order_id) ON DELETE CASCADE,
    material_id TEXT NOT NULL,
    binding TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, material_id)
);

INSERT INTO dalmaru.schema_migrations (version, description) VALUES (47, 'craft orders') ON CONFLICT DO NOTHING;
