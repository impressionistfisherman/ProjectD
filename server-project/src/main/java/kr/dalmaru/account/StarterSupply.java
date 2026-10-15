package kr.dalmaru.account;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One-time character supplies. The starter grant cannot be sold or traded. */
public final class StarterSupply {
	public static final String STARTER = "starter";
	private static final List<Line> LINES = load();
	private StarterSupply() { }

	public static void grant(Connection connection, UUID characterId) throws SQLException { grant(connection, characterId, STARTER); }

	public static void grant(Connection connection, UUID characterId, String key) throws SQLException {
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_supply_grants (character_id, grant_key) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
			insert.setObject(1, characterId);
			insert.setString(2, key);
			if (insert.executeUpdate() != 1) return;
		}
		for (Line line : LINES) if (line.grant.equals(key)) apply(connection, characterId, line);
	}

	public static boolean tradable(String itemId) {
		for (Line line : LINES) if (line.item.equals(itemId) && !line.kind.equals("copper")) return false;
		return true;
	}

	private static void apply(Connection connection, UUID characterId, Line line) throws SQLException {
		switch (line.kind) {
			case "equipment" -> {
				for (int i = 0; i < line.quantity; i++) try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_equipment (equipment_id, character_id, slot_id, item_id, required_level, bound, location) VALUES (?, ?, NULL, ?, 1, TRUE, 'INVENTORY')")) {
					insert.setObject(1, UUID.randomUUID());
					insert.setObject(2, characterId);
					insert.setString(3, line.item);
					insert.executeUpdate();
				}
			}
			case "material" -> {
				try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_materials (character_id, material_id, quantity) VALUES (?, ?, ?) ON CONFLICT (character_id, material_id) DO UPDATE SET quantity = dalmaru.character_materials.quantity + EXCLUDED.quantity")) {
					insert.setObject(1, characterId);
					insert.setString(2, line.item);
					insert.setInt(3, line.quantity);
					insert.executeUpdate();
				}
			}
			case "copper" -> {
				try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_wallet (character_id, copper) VALUES (?, ?) ON CONFLICT (character_id) DO UPDATE SET copper = dalmaru.character_wallet.copper + EXCLUDED.copper")) {
					insert.setObject(1, characterId);
					insert.setInt(2, line.quantity);
					insert.executeUpdate();
				}
			}
			default -> throw new IllegalArgumentException("Unknown supply kind: " + line.kind);
		}
	}

	private static List<Line> load() {
		try (InputStream stream = StarterSupply.class.getResourceAsStream("/content/pilot.json")) {
			if (stream == null) throw new IllegalStateException("Missing pilot content definitions");
			var root = JsonParser.parseString(new String(stream.readNBytes(1_048_576), StandardCharsets.UTF_8)).getAsJsonObject();
			var lines = new ArrayList<Line>();
			for (var element : root.getAsJsonArray("supplies")) {
				var line = element.getAsJsonObject();
				lines.add(new Line(line.get("grant").getAsString(), line.get("kind").getAsString(), line.get("item").getAsString(), line.get("quantity").getAsInt()));
			}
			if (lines.stream().noneMatch(line -> line.grant.equals(STARTER) && line.kind.equals("equipment") && line.item.equals("supply.training_gear") && line.quantity == 1)
				|| lines.stream().noneMatch(line -> line.grant.equals(STARTER) && line.kind.equals("material") && line.item.equals("supply.recovery") && line.quantity == 4)
				|| lines.stream().noneMatch(line -> line.grant.equals(STARTER) && line.kind.equals("copper") && line.quantity == 20)) throw new IllegalStateException("Starter supply definition is incomplete");
			return List.copyOf(lines);
		} catch (java.io.IOException exception) {
			throw new IllegalStateException("Cannot load starter supplies", exception);
		}
	}

	private record Line(String grant, String kind, String item, int quantity) { }
}
