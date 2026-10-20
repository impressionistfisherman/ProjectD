package kr.dalmaru;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterProfile;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.life.LifeProfessionRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5LifeProficiencyCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a545");
	private P5LifeProficiencyCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		require(LifeProfessionRepository.JOBS.size() == 9, "Life jobs are not nine.");
		String screen = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruLifeScreen.java"));
		String village = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruVillageScreen.java"));
		String server = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
		String gather = Files.readString(Path.of("src/main/java/kr/dalmaru/gather/Gathering.java"));
		require(village.contains("생활") && server.contains("\"learn_life\"") && !gather.contains("character_life_professions"), "Life entry is not wired beside gather XP.");
		for (String label : new String[]{"대장장이", "궁장", "목수", "재봉사", "세공사", "요리사", "연금술사", "약사", "부적사"}) require(screen.contains(label), "Life screen is missing " + label);
		FixtureMigrations.apply();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var life = new LifeProfessionRepository();
		try {
			long generation = accounts.openSession(ACCOUNT).connectionGeneration();
			UUID lead = characters.create(ACCOUNT, 1, "달마루_생활장").characterId();
			UUID other = characters.create(ACCOUNT, 2, "달마루_생활이").characterId();
			characters.selectActive(ACCOUNT, lead);
			sql("INSERT INTO dalmaru.character_life_xp(character_id, xp) VALUES(?, 7)", lead);
			CharacterProfile before = characters.profile(lead);
			reject(() -> learn(life, generation, 1, lead, "miner"));
			var learned = learn(life, generation, 2, lead, "blacksmith");
			var replay = learn(life, generation, 2, lead, "blacksmith");
			require(!learned.replayed() && replay.replayed() && proficiency(lead, "blacksmith") == 1 && count(lead) == 1, "Learn did not grant proficiency 1 once.");
			reject(() -> learn(life, generation, 3, lead, "blacksmith"));
			require(count(lead) == 1 && proficiency(lead, "blacksmith") == 1, "A second learn changed the row.");
			for (String job : LifeProfessionRepository.JOBS) if (!"blacksmith".equals(job)) learn(life, generation, 10 + LifeProfessionRepository.JOBS.indexOf(job), lead, job);
			require(count(lead) == 9 && count(other) == 0 && xp(lead) == 7 && xp(other) == 0, "Jobs or gather XP were not kept per character.");
			CharacterProfile after = characters.profile(lead);
			require(before.equals(after), "Learning a life job changed combat stats.");
			var capped = gain(life, generation, 30, lead, "blacksmith", 99);
			require(!capped.replayed() && proficiency(lead, "blacksmith") == 100, "Proficiency did not stop at 100.");
			reject(() -> gain(life, generation, 31, lead, "blacksmith", 1));
			require(proficiency(lead, "blacksmith") == 100, "A gain past 100 was stored.");
			characters.selectActive(ACCOUNT, other);
			reject(() -> learn(life, generation, 40, lead, "cook"));
			require(proficiency(lead, "cook") == 1, "A stale character learned a job.");
			characters.selectActive(ACCOUNT, lead);
			sql("UPDATE dalmaru.character_life_professions SET proficiency=1 WHERE character_id=? AND job='bowyer'", lead);
			var start = new CountDownLatch(1);
			var done = new CountDownLatch(2);
			var failure = new AtomicReference<Throwable>();
			for (int i = 0; i < 2; i++) {
				long request = 50 + i;
				new Thread(() -> {
					try { start.await(); gain(life, generation, request, lead, "bowyer", 1); }
					catch (Throwable error) { failure.set(error); }
					finally { done.countDown(); }
				}).start();
			}
			start.countDown();
			done.await();
			if (failure.get() != null) throw new IllegalStateException(failure.get());
			require(proficiency(lead, "bowyer") == 3, "Concurrent gains lost a point.");
			System.out.println("Nine life professions stay between 1 and 100 without changing combat stats or gather XP PASS");
		} finally {
			cleanup();
		}
	}

	private static LifeProfessionRepository.Learned learn(LifeProfessionRepository life, long generation, long request, UUID character, String job) throws Exception {
		var body = new JsonObject();
		body.addProperty("job", job);
		return life.learnJournaled(ACCOUNT, generation, request, character, job, RequestEnvelope.legacy(ACCOUNT, generation, character, "learn_life", request, body));
	}

	private static LifeProfessionRepository.Learned gain(LifeProfessionRepository life, long generation, long request, UUID character, String job, int amount) throws Exception {
		var body = new JsonObject();
		body.addProperty("job", job);
		body.addProperty("amount", amount);
		return life.gainJournaled(ACCOUNT, generation, request, character, job, amount, RequestEnvelope.legacy(ACCOUNT, generation, character, "gain_life", request, body));
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Life request was accepted.");
	}

	private static int proficiency(UUID character, String job) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT proficiency FROM dalmaru.character_life_professions WHERE character_id=? AND job=?")) {
			statement.setObject(1, character);
			statement.setString(2, job);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
		}
	}

	private static int count(UUID character) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.character_life_professions WHERE character_id=?")) {
			statement.setObject(1, character);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static int xp(UUID character) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT xp FROM dalmaru.character_life_xp WHERE character_id=?")) {
			statement.setObject(1, character);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
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
