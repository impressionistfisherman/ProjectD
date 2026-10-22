package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.account.StarterSupply;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.equipment.EquipmentRepository;
import kr.dalmaru.equipment.PilotEquipment;

public final class StarterSupplyCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a501");
	private StarterSupplyCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		if (ContentCatalog.current().version() == null) throw new IllegalStateException("Pilot content did not load.");
		applyMigration();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		try {
			var session = accounts.openSession(ACCOUNT);
			var created = characters.create(ACCOUNT, session.connectionGeneration(), 501, 1, "달마루_시작물품");
			if (created.replayed()) throw new IllegalStateException("Initial creation was treated as replay.");
			UUID first = created.character().characterId();
			require(supply(first), "Starter supply was not granted once.");
			var replayed = characters.create(ACCOUNT, session.connectionGeneration(), 501, 1, "달마루_시작물품");
			require(replayed.replayed() && first.equals(replayed.character().characterId()) && supply(first), "Replayed creation granted the starter supply again.");
			try (var connection = connection()) { StarterSupply.grant(connection, first); }
			require(supply(first), "A second starter grant changed the supply.");
			require(!PilotEquipment.tradable("supply.training_gear") && !PilotEquipment.tradable("supply.recovery") && !PilotEquipment.tradable("pilot.weapon.starter_sword.1") && PilotEquipment.tradable("pilot.weapon.two_handed_sword.20"), "Starter goods were tradable.");
			var sword = ContentCatalog.current().items().get("pilot.weapon.starter_sword.1");
			var profile = characters.profile(first);
			require(sword.level() == 1 && sword.requirement().isBlank() && sword.attack() > 0 && sword.attack() < 30 && profile.level() == 1 && profile.profession().isBlank(), "Starter sword is not equippable at level 1.");
			require(!Files.readString(Path.of("src/main/resources/content/shop.json")).contains("pilot.weapon.starter_sword.1"), "Starter sword was added to the shop.");
			characters.selectActive(ACCOUNT, session.connectionGeneration(), 701, first);
			new EquipmentRepository().move(ACCOUNT, session.connectionGeneration(), 702, equipmentId(first), true);
			require(count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id='pilot.weapon.starter_sword.1' AND slot_id=0 AND location='EQUIPPED'", first) == 1, "Level 1 could not equip the starter sword.");
			var second = characters.create(ACCOUNT, 2, "달마루_시작물품2");
			require(supply(second.characterId()), "A second character did not receive a separate starter supply.");
			try (var connection = connection(); var replace = connection.prepareStatement("UPDATE dalmaru.character_equipment SET item_id='pilot.weapon.sword_shield.10', required_level=10 WHERE character_id=? AND item_id='pilot.weapon.starter_sword.1'")) {
				replace.setObject(1, second.characterId());
				require(replace.executeUpdate() == 1, "Could not represent an older starter sword.");
				StarterSupply.grant(connection, second.characterId());
			}
			require(count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id='pilot.weapon.starter_sword.1'", second.characterId()) == 0
				&& count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id='pilot.weapon.sword_shield.10'", second.characterId()) == 1, "An older sword_shield grant was replaced.");
			System.out.println("Starter supply once, replay, and no trade PASS");
		} finally {
			cleanup();
		}
	}

	private static boolean supply(UUID character) throws Exception {
		return count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", character) == 20
			&& count("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='supply.recovery'", character) == 4
			&& count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id='supply.training_gear' AND bound AND location='INVENTORY'", character) == 1
			&& count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id='pilot.weapon.starter_sword.1' AND bound AND location='INVENTORY'", character) == 1
			&& count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id='pilot.weapon.sword_shield.10'", character) == 0
			&& count("SELECT count(*) FROM dalmaru.character_supply_grants WHERE character_id=? AND grant_key='starter'", character) == 1;
	}

	private static void applyMigration() throws Exception {
		try (var connection = connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=28")) {
			try (var result = check.executeQuery()) { if (result.next()) return; }
			try (var statement = connection.createStatement()) { statement.execute(Files.readString(Path.of("database", "migrations", "V028__character_supply_grants.sql"))); }
		}
	}

	private static UUID equipmentId(UUID character) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT equipment_id FROM dalmaru.character_equipment WHERE character_id=? AND item_id='pilot.weapon.starter_sword.1'")) {
			statement.setObject(1, character);
			try (var result = statement.executeQuery()) { result.next(); return result.getObject(1, UUID.class); }
		}
	}

	private static int count(String sql, UUID character) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, character);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		String password = System.getenv("DALMARU_DB_PASSWORD");
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), password);
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }

	private static void cleanup() throws Exception {
		try (var connection = connection()) {
			for (String sql : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
				"DELETE FROM dalmaru.character_creation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_selection_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_equipment_move_requests WHERE account_id=?",
				"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_supply_grants WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_equipment WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.characters WHERE account_id=?",
				"DELETE FROM dalmaru.accounts WHERE account_id=?"
			}) try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); }
		}
	}
}
