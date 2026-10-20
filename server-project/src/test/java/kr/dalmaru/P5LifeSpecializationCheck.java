package kr.dalmaru;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterProfile;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.life.LifeProfessionRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5LifeSpecializationCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a546");
	private P5LifeSpecializationCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		String repo = Files.readString(Path.of("src/main/java/kr/dalmaru/life/LifeProfessionRepository.java"));
		String spec = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruLifeSpecScreen.java"));
		String life = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruLifeScreen.java"));
		require(!repo.toLowerCase().contains("raid") && life.contains("전문화") && spec.contains("명장 과제"), "Specialization kept a raid material or lost its screen.");
		FixtureMigrations.apply();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var jobs = new LifeProfessionRepository();
		try {
			long generation = accounts.openSession(ACCOUNT).connectionGeneration();
			UUID character = characters.create(ACCOUNT, 1, "달마루_전문장").characterId();
			characters.selectActive(ACCOUNT, character);
			wallet(character, 20);
			sql("INSERT INTO dalmaru.character_life_xp(character_id, xp) VALUES(?, 7)", character);
			learn(jobs, generation, 1, character);
			sql("UPDATE dalmaru.character_life_professions SET proficiency=20 WHERE character_id=? AND job='blacksmith'", character);
			reject(() -> specialize(jobs, generation, 2, character, "blacksmith.journeyman"));
			sql("UPDATE dalmaru.character_life_professions SET proficiency=21 WHERE character_id=? AND job='blacksmith'", character);
			reject(() -> specialize(jobs, generation, 3, character, "blacksmith.novice"));
			var first = specialize(jobs, generation, 4, character, "blacksmith.journeyman");
			var replay = specialize(jobs, generation, 4, character, "blacksmith.journeyman");
			require(!first.replayed() && first.result().get("copperSpent").getAsInt() == 0 && replay.replayed() && "blacksmith.journeyman".equals(specialization(character)) && copper(character) == 20 && proficiency(character) == 21, "First specialization was not free once.");
			reject(() -> specialize(jobs, generation, 5, character, "blacksmith.journeyman"));
			sql("UPDATE dalmaru.character_life_professions SET proficiency=41 WHERE character_id=? AND job='blacksmith'", character);
			wallet(character, 499);
			reject(() -> specialize(jobs, generation, 6, character, "blacksmith.expert"));
			require("blacksmith.journeyman".equals(specialization(character)) && copper(character) == 499 && proficiency(character) == 41, "A short balance changed the specialization.");
			wallet(character, 500);
			CharacterProfile before = characters.profile(character);
			var expert = specialize(jobs, generation, 7, character, "blacksmith.expert");
			var expertReplay = specialize(jobs, generation, 7, character, "blacksmith.expert");
			require(!expert.replayed() && expert.result().get("copperSpent").getAsInt() == 500 && expertReplay.replayed() && copper(character) == 0 && proficiency(character) == 41 && xp(character) == 7 && before.equals(characters.profile(character)) && ledger(character) == 1, "The 500 copper change was not charged once.");
			reject(() -> task(jobs, generation, 8, character));
			sql("UPDATE dalmaru.character_life_professions SET proficiency=81 WHERE character_id=? AND job='blacksmith'", character);
			wallet(character, 500);
			specialize(jobs, generation, 9, character, "blacksmith.grandmaster");
			task(jobs, generation, 10, character);
			var taskReplay = task(jobs, generation, 10, character);
			require(taskReplay.replayed() && "done".equals(taskState(character)) && proficiency(character) == 81 && copper(character) == 0 && xp(character) == 7 && before.equals(characters.profile(character)), "The masterwork task changed proficiency, copper, or combat stats.");
			reject(() -> task(jobs, generation, 11, character));
			require("done".equals(taskState(character)) && ledger(character) == 2, "A second task or a second charge was stored.");
			System.out.println("Life specialization is free once, then 500 copper, and the masterwork task stores no raid material PASS");
		} finally {
			cleanup();
		}
	}

	private static LifeProfessionRepository.Learned learn(LifeProfessionRepository jobs, long generation, long request, UUID character) throws Exception {
		var body = new JsonObject();
		body.addProperty("job", "blacksmith");
		return jobs.learnJournaled(ACCOUNT, generation, request, character, "blacksmith", RequestEnvelope.legacy(ACCOUNT, generation, character, "learn_life", request, body));
	}

	private static LifeProfessionRepository.Learned specialize(LifeProfessionRepository jobs, long generation, long request, UUID character, String specialization) throws Exception {
		var body = new JsonObject();
		body.addProperty("job", "blacksmith");
		body.addProperty("specialization", specialization);
		return jobs.specializeJournaled(ACCOUNT, generation, request, character, "blacksmith", specialization, RequestEnvelope.legacy(ACCOUNT, generation, character, "specialize_life", request, body));
	}

	private static LifeProfessionRepository.Learned task(LifeProfessionRepository jobs, long generation, long request, UUID character) throws Exception {
		var body = new JsonObject();
		body.addProperty("job", "blacksmith");
		return jobs.completeTaskJournaled(ACCOUNT, generation, request, character, "blacksmith", RequestEnvelope.legacy(ACCOUNT, generation, character, "complete_life_task", request, body));
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Life specialization was accepted.");
	}

	private static String specialization(UUID character) throws Exception { return text("SELECT specialization FROM dalmaru.character_life_professions WHERE character_id=? AND job='blacksmith'", character); }
	private static String taskState(UUID character) throws Exception { return text("SELECT task_state FROM dalmaru.character_life_professions WHERE character_id=? AND job='blacksmith'", character); }
	private static int proficiency(UUID character) throws Exception { return number("SELECT proficiency FROM dalmaru.character_life_professions WHERE character_id=? AND job='blacksmith'", character); }
	private static int copper(UUID character) throws Exception { return number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", character); }
	private static int xp(UUID character) throws Exception { return number("SELECT xp FROM dalmaru.character_life_xp WHERE character_id=?", character); }
	private static int ledger(UUID character) throws Exception { return number("SELECT COUNT(*) FROM dalmaru.character_currency_ledger WHERE character_id=? AND reason='life_specialization' AND copper_delta=-500", character); }

	private static void wallet(UUID character, int copper) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("INSERT INTO dalmaru.character_wallet(character_id, copper) VALUES(?, ?) ON CONFLICT(character_id) DO UPDATE SET copper=EXCLUDED.copper")) {
			statement.setObject(1, character);
			statement.setInt(2, copper);
			statement.executeUpdate();
		}
	}

	private static String text(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { return result.next() ? result.getString(1) : ""; }
		}
	}

	private static int number(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static void sql(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) { statement.setObject(1, id); statement.executeUpdate(); }
	}

	private static java.sql.Connection connection() throws Exception {
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup() throws Exception {
		try (var connection = connection()) {
			for (String query : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
				"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_currency_ledger WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_life_professions WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_life_xp WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.characters WHERE account_id=?",
				"DELETE FROM dalmaru.accounts WHERE account_id=?"
			}) try (var statement = connection.prepareStatement(query)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); }
		}
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
