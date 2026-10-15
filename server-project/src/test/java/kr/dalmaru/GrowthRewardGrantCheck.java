package kr.dalmaru;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.account.StarterSupply;
import kr.dalmaru.equipment.PilotEquipment;

public final class GrowthRewardGrantCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a502");
	private GrowthRewardGrantCheck() { }

	public static void main(String[] args) throws Exception {
		if (!"grok_p3_fixture".equals(System.getenv("DALMARU_DB_NAME"))) throw new IllegalStateException("Refusing to run outside grok_p3_fixture.");
		try (var connection = connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=28")) {
			try (var result = check.executeQuery()) { if (!result.next()) try (var statement = connection.createStatement()) { statement.execute(java.nio.file.Files.readString(java.nio.file.Path.of("database", "migrations", "V028__character_supply_grants.sql"))); } }
		}
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		try {
			accounts.openSession(ACCOUNT);
			UUID warrior = characters.create(ACCOUNT, 1, "달마루_전직보상").characterId();
			UUID archer = characters.create(ACCOUNT, 2, "달마루_궁수보상").characterId();
			UUID bare = characters.create(ACCOUNT, 3, "달마루_무직보상").characterId();
			profession(warrior, "WARRIOR");
			profession(archer, "ARCHER");
			boolean rejected = false;
			try (var connection = connection()) {
				connection.setAutoCommit(false);
				try { StarterSupply.grant(connection, bare, StarterSupply.INVESTIGATION); }
				catch (IllegalArgumentException expected) { rejected = true; }
				connection.rollback();
			}
			require(rejected && count("SELECT count(*) FROM dalmaru.character_supply_grants WHERE character_id=? AND grant_key='investigation'", bare) == 0, "Investigation without a profession was stored.");
			grant(warrior, StarterSupply.PROFESSION);
			require(warriorKit(warrior, 10) && count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", warrior) == 20 && count("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='supply.recovery'", warrior) == 4, "Profession kit was not granted once.");
			grant(warrior, StarterSupply.PROFESSION);
			require(warriorKit(warrior, 10), "Profession kit was granted twice.");
			try (var connection = connection(); var update = connection.prepareStatement("UPDATE dalmaru.character_equipment SET enhancement=3 WHERE character_id=? AND item_id='pilot.weapon.two_handed_sword.10'")) {
				update.setObject(1, warrior);
				require(update.executeUpdate() == 1, "Granted weapon was missing.");
			}
			grant(warrior, StarterSupply.INVESTIGATION);
			require(warriorKit(warrior, 15) && count("SELECT enhancement FROM dalmaru.character_equipment WHERE character_id=? AND item_id='pilot.weapon.two_handed_sword.15'", warrior) == 3, "Level 15 replacement did not keep the weapon.");
			require(count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", warrior) == 60 && count("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='supply.recovery'", warrior) == 8 && count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id='supply.training_gear'", warrior) == 1, "Investigation reward changed the wrong supply.");
			grant(warrior, StarterSupply.INVESTIGATION);
			require(count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", warrior) == 60, "Investigation reward was paid twice.");
			grant(archer, StarterSupply.PROFESSION);
			require(count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id LIKE 'pilot.weapon.bow%' OR character_id=? AND item_id LIKE 'pilot.weapon.crossbow%'", archer) == 2 && count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id LIKE 'pilot.armor.light.%'", archer) == 4 && count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND (item_id LIKE 'pilot.armor.heavy.%' OR item_id LIKE 'pilot.weapon.two_handed_sword%')", archer) == 0, "Archer received another profession's gear.");
			require(!PilotEquipment.tradable("pilot.weapon.two_handed_sword.10") && !PilotEquipment.tradable("pilot.weapon.two_handed_sword.15") && PilotEquipment.tradable("pilot.weapon.two_handed_sword.20") && PilotEquipment.tradable("pilot.accessory.neck.10"), "Profession kit tradable flag is wrong.");
			System.out.println("Profession kit once, investigation replacement once, other profession untouched PASS");
		} finally {
			cleanup();
		}
	}

	private static boolean warriorKit(UUID character, int level) throws Exception {
		String tier = "." + level;
		return count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id IN ('pilot.weapon.two_handed_sword" + tier + "','pilot.weapon.sword_shield" + tier + "') AND bound AND required_level=" + level, character) == 2
			&& count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id IN ('pilot.armor.heavy.head" + tier + "','pilot.armor.heavy.body" + tier + "','pilot.armor.heavy.arms" + tier + "','pilot.armor.heavy.feet" + tier + "') AND bound AND required_level=" + level, character) == 4
			&& count("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id LIKE '%.10' AND item_id LIKE 'pilot.%'", character) == (level == 10 ? 6 : 0);
	}

	private static void grant(UUID character, String key) throws Exception {
		try (var connection = connection()) {
			connection.setAutoCommit(false);
			try { StarterSupply.grant(connection, character, key); connection.commit(); }
			catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private static void profession(UUID character, String job) throws Exception {
		try (var connection = connection(); var insert = connection.prepareStatement("INSERT INTO dalmaru.character_professions (character_id, base_job) VALUES (?, ?)")) {
			insert.setObject(1, character);
			insert.setString(2, job);
			insert.executeUpdate();
		}
	}

	private static int count(String sql, UUID character) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, character);
			if (sql.chars().filter(ch -> ch == '?').count() > 1) statement.setObject(2, character);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }

	private static void cleanup() throws Exception {
		try (var connection = connection()) {
			for (String sql : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
				"DELETE FROM dalmaru.character_creation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_professions WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
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
