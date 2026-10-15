package kr.dalmaru;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.gather.GatherCatalog;
import kr.dalmaru.gather.Gathering;
import kr.dalmaru.request.RequestEnvelope;

public final class GatherNodeCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a534");
	private GatherNodeCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		reject(() -> GatherCatalog.node("missing.node"));
		applyMigration();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var gathering = new Gathering();
		try {
			long[] generation = { accounts.openSession(ACCOUNT).connectionGeneration() };
			UUID lead = characters.create(ACCOUNT, 1, "달마루_채집").characterId();
			UUID partner = characters.create(ACCOUNT, 2, "달마루_생활").characterId();
			characters.selectActive(ACCOUNT, lead);
			reject(() -> take(gathering, generation[0], 1, lead, 1000, 64, 0));
			require(material(lead) == 0 && xp(lead) == 0 && !cooling(), "A miss started a gather cooldown.");
			var taken = take(gathering, generation[0], 2, lead, 200, 64, 200);
			var replay = take(gathering, generation[0], 2, lead, 200, 64, 200);
			require(!taken.replayed() && replay.replayed() && material(lead) == 1 && xp(lead) == 5 && cooling(), "Gather did not grant once.");
			reject(() -> take(gathering, generation[0], 3, lead, 200, 64, 200));
			generation[0] = accounts.openSession(ACCOUNT).connectionGeneration();
			reject(() -> take(gathering, generation[0], 4, lead, 200, 64, 200));
			characters.selectActive(ACCOUNT, partner);
			reject(() -> take(gathering, generation[0], 5, partner, 200, 64, 200));
			require(xp(lead) == 5 && xp(partner) == 0 && material(partner) == 0, "Account cooldown did not cover the other character.");
			sql("UPDATE dalmaru.account_gather_cooldowns SET ready_at=clock_timestamp() - interval '1 second' WHERE account_id=?");
			take(gathering, generation[0], 6, partner, 200, 64, 200);
			require(material(partner) == 1 && xp(partner) == 5 && xp(lead) == 5, "Life experience was not kept per character.");
			sql("UPDATE dalmaru.character_materials SET binding='CHARACTER' WHERE character_id=? AND material_id='material.ironstone'", lead);
			sql("UPDATE dalmaru.account_gather_cooldowns SET ready_at=clock_timestamp() - interval '1 second' WHERE account_id=?");
			characters.selectActive(ACCOUNT, lead);
			reject(() -> take(gathering, generation[0], 7, lead, 200, 64, 200));
			require(material(lead) == 1 && binding(lead).equals("CHARACTER") && xp(lead) == 5, "Character-bound material was gathered into.");
			System.out.println("Gather nodes keep an account cooldown and character life experience PASS");
		} finally {
			cleanup();
		}
	}

	private static Gathering.Journaled take(Gathering gathering, long generation, long request, UUID character, int x, int y, int z) throws Exception {
		var body = new JsonObject();
		body.addProperty("nodeId", "gather.ironstone");
		body.addProperty("x", 999);
		var envelope = RequestEnvelope.legacy(ACCOUNT, generation, character, "gather_node", request, body);
		return gathering.actJournaled(ACCOUNT, generation, request, character, "gather.ironstone", "minecraft:overworld", x, y, z, envelope);
	}

	private static void applyMigration() throws Exception {
		try (var connection = connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=34")) {
			try (var result = check.executeQuery()) { if (result.next()) return; }
			try (var statement = connection.createStatement()) { statement.execute(Files.readString(Path.of("database", "migrations", "V034__gather_cooldowns.sql"))); }
		}
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Gather accepted a blocked request.");
	}

	private static void sql(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) { statement.setObject(1, id); statement.executeUpdate(); }
	}

	private static void sql(String query) throws Exception { sql(query, ACCOUNT); }

	private static int material(UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.ironstone'")) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
		}
	}

	private static String binding(UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT binding FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.ironstone'")) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
		}
	}

	private static int xp(UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT xp FROM dalmaru.character_life_xp WHERE character_id=?")) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
		}
	}

	private static boolean cooling() throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT ready_at > clock_timestamp() FROM dalmaru.account_gather_cooldowns WHERE account_id=? AND node_id='gather.ironstone'")) {
			statement.setObject(1, ACCOUNT);
			try (var result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
		}
	}

	private static java.sql.Connection connection() throws Exception {
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup() throws Exception {
		try (var connection = connection()) {
			for (String query : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
				"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.account_gather_cooldowns WHERE account_id=?",
				"DELETE FROM dalmaru.character_life_xp WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.characters WHERE account_id=?",
				"DELETE FROM dalmaru.accounts WHERE account_id=?"
			}) try (var statement = connection.prepareStatement(query)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); }
		}
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
