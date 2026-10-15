package kr.dalmaru.account;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.content.ContentCatalog;

/** One-time supplies. Starter and the level 10/15 profession kit cannot be sold or traded. */
public final class StarterSupply {
	public static final String STARTER = "starter";
	public static final String PROFESSION = "profession";
	public static final String INVESTIGATION = "investigation";
	private static final List<Line> LINES = load();
	private StarterSupply() { }

	public static void grant(Connection connection, UUID characterId) throws SQLException { grant(connection, characterId, STARTER); }

	public static void grant(Connection connection, UUID characterId, String key) throws SQLException {
		String job = PROFESSION.equals(key) || INVESTIGATION.equals(key) ? profession(connection, characterId) : null;
		if ((PROFESSION.equals(key) || INVESTIGATION.equals(key)) && job == null) throw new IllegalArgumentException("Profession is required.");
		if (!claim(connection, characterId, key)) return;
		if (PROFESSION.equals(key)) give(connection, characterId, kit(job, 10));
		else if (INVESTIGATION.equals(key)) {
			for (Line line : LINES) if (line.grant.equals(key)) apply(connection, characterId, line);
			replace(connection, characterId, kit(job, 10));
		} else for (Line line : LINES) if (line.grant.equals(key)) apply(connection, characterId, line);
	}

	public static boolean tradable(String itemId) {
		for (Line line : LINES) if (line.item.equals(itemId) && !line.kind.equals("copper")) return false;
		var item = ContentCatalog.current().items().get(itemId);
		return item == null || item.requirement().isBlank() || (item.level() != 10 && item.level() != 15);
	}

	private static boolean claim(Connection connection, UUID characterId, String key) throws SQLException {
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_supply_grants (character_id, grant_key) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
			insert.setObject(1, characterId);
			insert.setString(2, key);
			return insert.executeUpdate() == 1;
		}
	}

	private static String profession(Connection connection, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT base_job FROM dalmaru.character_professions WHERE character_id=?")) {
			query.setObject(1, characterId);
			try (var row = query.executeQuery()) { return row.next() ? row.getString(1) : null; }
		}
	}

	private static List<String> kit(String job, int level) {
		String armor = switch (job) { case "WARRIOR" -> "heavy"; case "ARCHER", "ROGUE" -> "light"; case "MAGE" -> "cloth"; default -> throw new IllegalArgumentException("Unknown profession."); };
		var ids = ContentCatalog.current().items().entrySet().stream()
			.filter(entry -> entry.getValue().enabled() && entry.getValue().level() == level && (entry.getValue().requirement().equals(job) || entry.getValue().requirement().equals(armor)))
			.map(java.util.Map.Entry::getKey).sorted().toList();
		if (ids.size() != 6) throw new IllegalStateException("Profession kit is incomplete.");
		return ids;
	}

	private static void give(Connection connection, UUID characterId, List<String> itemIds) throws SQLException {
		for (String itemId : itemIds) insertEquipment(connection, characterId, itemId);
	}

	private static void replace(Connection connection, UUID characterId, List<String> levelTen) throws SQLException {
		for (String itemId : levelTen) {
			if (!itemId.endsWith(".10")) throw new IllegalStateException("Profession kit id is not level 10.");
			String next = itemId.substring(0, itemId.length() - 3) + ".15";
			try (var update = connection.prepareStatement("UPDATE dalmaru.character_equipment SET item_id=?, required_level=? WHERE character_id=? AND item_id=?")) {
				update.setString(1, next);
				update.setInt(2, ContentCatalog.current().items().get(next).level());
				update.setObject(3, characterId);
				update.setString(4, itemId);
				if (update.executeUpdate() == 0) insertEquipment(connection, characterId, next);
			}
		}
	}

	private static void insertEquipment(Connection connection, UUID characterId, String itemId) throws SQLException {
		var defined = ContentCatalog.current().items().get(itemId);
		int level = defined == null ? 1 : defined.level();
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_equipment (equipment_id, character_id, slot_id, item_id, required_level, bound, location) VALUES (?, ?, NULL, ?, ?, TRUE, 'INVENTORY')")) {
			insert.setObject(1, UUID.randomUUID());
			insert.setObject(2, characterId);
			insert.setString(3, itemId);
			insert.setInt(4, level);
			insert.executeUpdate();
		}
	}

	private static void apply(Connection connection, UUID characterId, Line line) throws SQLException {
		switch (line.kind) {
			case "equipment" -> { for (int i = 0; i < line.quantity; i++) insertEquipment(connection, characterId, line.item); }
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
				|| lines.stream().noneMatch(line -> line.grant.equals(STARTER) && line.kind.equals("copper") && line.quantity == 20)
				|| lines.stream().noneMatch(line -> line.grant.equals(INVESTIGATION) && line.kind.equals("copper") && line.quantity == 40)
				|| lines.stream().noneMatch(line -> line.grant.equals(INVESTIGATION) && line.kind.equals("material") && line.item.equals("supply.recovery") && line.quantity == 4)) throw new IllegalStateException("Supply definition is incomplete");
			return List.copyOf(lines);
		} catch (java.io.IOException exception) {
			throw new IllegalStateException("Cannot load supplies", exception);
		}
	}

	private record Line(String grant, String kind, String item, int quantity) { }
}
