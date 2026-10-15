package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.account.StarterSupply;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.equipment.PilotEquipment;

public final class StarterSupplyCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a501");
	private StarterSupplyCheck() { }

	public static void main(String[] args) throws Exception {
		if (!"oscar_p2_fixture".equals(System.getenv("DALMARU_DB_NAME"))) throw new IllegalStateException("Refusing to run outside oscar_p2_fixture.");
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
			require(!PilotEquipment.tradable("supply.training_gear") && !PilotEquipment.tradable("supply.recovery") && PilotEquipment.tradable("pilot.weapon.two_handed_sword.20"), "Starter goods were tradable.");
			var second = characters.create(ACCOUNT, 2, "달마루_시작물품2");
			require(supply(second.characterId()) && supply(first), "A second character did not receive a separate starter supply.");
			System.out.println("Starter supply once, replay, and no trade PASS");
		} finally {
			cleanup();
		}
	}

	private static boolean supply(UUID character) throws Exception {
		return count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", character) == 20
			&& count("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='supply.recovery'", character) == 4
			&& count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id='supply.training_gear' AND bound AND location='INVENTORY'", character) == 1
			&& count("SELECT count(*) FROM dalmaru.character_supply_grants WHERE character_id=? AND grant_key='starter'", character) == 1;
	}

	private static void applyMigration() throws Exception {
		try (var connection = connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=28")) {
			try (var result = check.executeQuery()) { if (result.next()) return; }
			try (var statement = connection.createStatement()) { statement.execute(Files.readString(Path.of("database", "migrations", "V028__character_supply_grants.sql"))); }
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
