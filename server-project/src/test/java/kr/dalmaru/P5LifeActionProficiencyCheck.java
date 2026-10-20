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
import kr.dalmaru.life.LifeProfessionRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5LifeActionProficiencyCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a5c1");
	private P5LifeActionProficiencyCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		String networking = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
		String gathering = Files.readString(Path.of("src/main/java/kr/dalmaru/gather/Gathering.java"));
		String crops = Files.readString(Path.of("src/main/java/kr/dalmaru/housing/CropRepository.java"));
		String production = Files.readString(Path.of("src/main/java/kr/dalmaru/craft/ProductionQueueRepository.java"));
		String craft = Files.readString(Path.of("src/main/java/kr/dalmaru/craft/CraftResearchRepository.java"));
		String client = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
		require(networking.contains("초행은 15레벨부터 입장할 수 있습니다.") && networking.contains("수련은 20레벨부터 입장할 수 있습니다.") && networking.contains("시련은 30레벨부터 입장할 수 있습니다.") && networking.contains("극한은 60레벨부터 입장할 수 있습니다."), "Level rejection text is not split by difficulty.");
		require(!networking.contains("\"gain_life\""), "A client can request a life proficiency gain.");
		require(gathering.contains("grantAction") && crops.contains("grantAction") && production.contains("grantAction") && craft.contains("grantAction") && !gathering.contains("character_life_professions"), "Life actions do not reuse the profession grant.");
		require(client.contains("rejectionText") && networking.contains("\"reason\""), "A rejection receipt has no reason.");
		require(GatherCatalog.node("gather.ironstone").cooldownSeconds() == 180, "Iron gather is not one hour to proficiency 21.");
		require("blacksmith".equals(LifeProfessionRepository.jobForMaterial("material.ironstone")) && "cook".equals(LifeProfessionRepository.jobForCrop("grain")) && "blacksmith".equals(LifeProfessionRepository.jobForBasicRecipe("craft.iron_ingot")), "Life action jobs are not mapped.");
		FixtureMigrations.apply();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var life = new LifeProfessionRepository();
		var gatheringApi = new Gathering();
		try {
			long generation = accounts.openSession(ACCOUNT).connectionGeneration();
			UUID lead = characters.create(ACCOUNT, 1, "달마루_숙련갑").characterId();
			UUID other = characters.create(ACCOUNT, 2, "달마루_숙련을").characterId();
			characters.selectActive(ACCOUNT, lead);
			learn(life, generation, 1, lead, "blacksmith");
			var taken = take(gatheringApi, generation, 2, lead);
			var replay = take(gatheringApi, generation, 2, lead);
			require(!taken.replayed() && replay.replayed() && proficiency(lead, "blacksmith") == 2 && xp(lead) == 5, "A gather did not grant one proficiency inside the same request.");
			sql("UPDATE dalmaru.account_gather_cooldowns SET ready_at=clock_timestamp() - interval '1 second' WHERE account_id=?");
			take(gatheringApi, generation, 3, lead);
			require(proficiency(lead, "blacksmith") == 3 && xp(lead) == 10, "A second gather did not grant another point.");
			sql("UPDATE dalmaru.character_life_professions SET proficiency=100 WHERE character_id=? AND job='blacksmith'", lead);
			try (var connection = connection()) { LifeProfessionRepository.grantAction(connection, lead, "blacksmith"); }
			sql("UPDATE dalmaru.account_gather_cooldowns SET ready_at=clock_timestamp() - interval '1 second' WHERE account_id=?");
			take(gatheringApi, generation, 4, lead);
			require(proficiency(lead, "blacksmith") == 100 && xp(lead) == 15, "The proficiency cap stopped the gather.");
			characters.selectActive(ACCOUNT, other);
			sql("UPDATE dalmaru.account_gather_cooldowns SET ready_at=clock_timestamp() - interval '1 second' WHERE account_id=?");
			take(gatheringApi, generation, 5, other);
			require(proficiency(other, "blacksmith") == 0 && count(other) == 0 && material(other) == 1 && xp(other) == 5, "An unlearned job blocked the gather.");
			System.out.println("Life actions grant one proficiency on the same request and level text names the difficulty PASS");
		} finally {
			cleanup();
		}
	}

	private static LifeProfessionRepository.Learned learn(LifeProfessionRepository life, long generation, long request, UUID character, String job) throws Exception {
		var body = new JsonObject();
		body.addProperty("job", job);
		return life.learnJournaled(ACCOUNT, generation, request, character, job, RequestEnvelope.legacy(ACCOUNT, generation, character, "learn_life", request, body));
	}

	private static Gathering.Journaled take(Gathering gathering, long generation, long request, UUID character) throws Exception {
		var body = new JsonObject();
		body.addProperty("nodeId", "gather.ironstone");
		body.addProperty("x", 999);
		return gathering.actJournaled(ACCOUNT, generation, request, character, "gather.ironstone", "minecraft:overworld", 200, 64, 200, RequestEnvelope.legacy(ACCOUNT, generation, character, "gather_node", request, body));
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

	private static int material(UUID character) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.ironstone'")) {
			statement.setObject(1, character);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
		}
	}

	private static void sql(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) { statement.setObject(1, id); statement.executeUpdate(); }
	}

	private static void sql(String query) throws Exception { sql(query, ACCOUNT); }

	private static java.sql.Connection connection() throws Exception {
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup() throws Exception {
		try (var connection = connection()) {
			for (String query : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
				"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.account_gather_cooldowns WHERE account_id=?",
				"DELETE FROM dalmaru.character_life_professions WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
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
}
