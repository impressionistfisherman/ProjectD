package kr.dalmaru;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.craft.CraftCatalog;
import kr.dalmaru.craft.CraftResearchRepository;
import kr.dalmaru.life.LifeProfessionRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5CraftResearchCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a547");
	private P5CraftResearchCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		require(CraftCatalog.recipe("craft.iron_ingot").output().itemId().equals("material.iron_ingot") && CraftCatalog.recipe("craft.basic_fitting").stationId().equals("station.forge"), "Basic recipes changed.");
		String repo = Files.readString(Path.of("src/main/java/kr/dalmaru/craft/CraftResearchRepository.java"));
		String village = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruVillageScreen.java"));
		String screen = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruCraftScreen.java"));
		require(!repo.contains("character_enhancement") && village.contains("제작") && screen.contains("연구"), "Craft research moved the enhancement ledger or lost its screen.");
		FixtureMigrations.apply();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var life = new LifeProfessionRepository();
		var craft = new CraftResearchRepository();
		try {
			long generation = accounts.openSession(ACCOUNT).connectionGeneration();
			UUID character = characters.create(ACCOUNT, 1, "달마루_제작연구").characterId();
			characters.selectActive(ACCOUNT, character);
			learn(life, generation, 1, character);
			sql("UPDATE dalmaru.character_life_professions SET proficiency=20 WHERE character_id=? AND job='blacksmith'", character);
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'FREE')", character);
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.ironstone', 1, 'FREE')", character);
			reject(() -> research(craft, generation, 2, character, "material.iron_ingot", "FREE", 16, 64, 16));
			sql("UPDATE dalmaru.character_life_professions SET proficiency=21 WHERE character_id=? AND job='blacksmith'", character);
			reject(() -> research(craft, generation, 3, character, "material.iron_ingot", "FREE", 1000, 64, 0));
			reject(() -> research(craft, generation, 4, character, "material.iron_ingot", "ACCOUNT", 16, 64, 16));
			reject(() -> researchMany(craft, generation, 5, character));
			reject(() -> research(craft, generation, 6, character, "material.ironstone", "FREE", 16, 64, 16));
			require(material(character, "material.iron_ingot") == 1 && material(character, "material.ironstone") == 1 && learned(character) == 0 && researchRows(character) == 0, "A rejected research consumed material or saved progress.");
			race(craft, generation, character);
			require(material(character, "material.iron_ingot") == 0 && learned(character) == 1 && material(character, "material.ironstone") == 1, "Concurrent research learned more than one recipe or kept the ingot.");
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'ACCOUNT')", character);
			raceCraft(craft, generation, character);
			require(material(character, "material.iron_ingot") == 0 && equipment(character) == 1 && enhancement(character) == 0 && "ACCOUNT".equals(equipmentBinding(character)), "Concurrent craft did not grant one bound, unenhanced piece.");
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'ACCOUNT')", character);
			var made = craft(craft, generation, 20, character);
			var replay = craft(craft, generation, 20, character);
			require(!made.replayed() && replay.replayed() && made.result().get("enhancement").getAsInt() == 0 && "ACCOUNT".equals(made.result().get("binding").getAsString()) && equipment(character) == 2, "Replay crafted a second piece.");
			reject(() -> craft(craft, generation, 21, character));
			require(equipment(character) == 2 && number("SELECT COALESCE(MAX(enhancement),0) FROM dalmaru.character_equipment WHERE character_id=?", character) == 0 && enhancements(character) == 0 && orders(character) == 0, "A failed craft or the reserved precision table changed stored gear.");
			System.out.println("Craft research learns one recipe and crafts one bound enhancement-zero piece PASS");
		} finally {
			cleanup();
		}
	}

	private static void race(CraftResearchRepository craft, long generation, UUID character) throws Exception {
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(2);
		var failure = new AtomicReference<Throwable>();
		for (int i = 0; i < 2; i++) {
			long request = 10 + i;
			new Thread(() -> {
				try { start.await(); research(craft, generation, request, character, "material.iron_ingot", "FREE", 16, 64, 16); }
				catch (IllegalArgumentException expected) { }
				catch (Throwable error) { failure.set(error); }
				finally { done.countDown(); }
			}).start();
		}
		start.countDown();
		done.await();
		if (failure.get() != null) throw new IllegalStateException(failure.get());
	}

	private static void raceCraft(CraftResearchRepository craft, long generation, UUID character) throws Exception {
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(2);
		var failure = new AtomicReference<Throwable>();
		for (int i = 0; i < 2; i++) {
			long request = 12 + i;
			new Thread(() -> {
				try { start.await(); craft(craft, generation, request, character); }
				catch (IllegalArgumentException expected) { }
				catch (Throwable error) { failure.set(error); }
				finally { done.countDown(); }
			}).start();
		}
		start.countDown();
		done.await();
		if (failure.get() != null) throw new IllegalStateException(failure.get());
	}

	private static void learn(LifeProfessionRepository life, long generation, long request, UUID character) throws Exception {
		var body = new JsonObject();
		body.addProperty("job", "blacksmith");
		life.learnJournaled(ACCOUNT, generation, request, character, "blacksmith", RequestEnvelope.legacy(ACCOUNT, generation, character, "learn_life", request, body));
	}

	private static CraftResearchRepository.Journaled research(CraftResearchRepository craft, long generation, long request, UUID character, String material, String binding, int x, int y, int z) throws Exception {
		var line = new JsonObject();
		line.addProperty("materialId", material);
		line.addProperty("binding", binding);
		line.addProperty("quantity", 1);
		var materials = new JsonArray();
		materials.add(line);
		var body = new JsonObject();
		body.addProperty("clueId", "clue.blade");
		body.add("materials", materials);
		return craft.researchJournaled(ACCOUNT, generation, request, character, "clue.blade", "minecraft:overworld", x, y, z, RequestEnvelope.legacy(ACCOUNT, generation, character, "research_craft", request, body));
	}

	private static void researchMany(CraftResearchRepository craft, long generation, long request, UUID character) throws Exception {
		var materials = new JsonArray();
		for (int i = 0; i < 9; i++) {
			var line = new JsonObject();
			line.addProperty("materialId", "material.extra" + i);
			line.addProperty("binding", "FREE");
			line.addProperty("quantity", 1);
			materials.add(line);
		}
		var body = new JsonObject();
		body.addProperty("clueId", "clue.blade");
		body.add("materials", materials);
		craft.researchJournaled(ACCOUNT, generation, request, character, "clue.blade", "minecraft:overworld", 16, 64, 16, RequestEnvelope.legacy(ACCOUNT, generation, character, "research_craft", request, body));
	}

	private static CraftResearchRepository.Journaled craft(CraftResearchRepository craft, long generation, long request, UUID character) throws Exception {
		var body = new JsonObject();
		body.addProperty("recipeId", "craft.iron_blade");
		return craft.craftJournaled(ACCOUNT, generation, request, character, "craft.iron_blade", "minecraft:overworld", 16, 64, 16, RequestEnvelope.legacy(ACCOUNT, generation, character, "craft_learned", request, body));
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Craft research was accepted.");
	}

	private static int material(UUID character, String item) throws Exception { return number("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='" + item + "'", character); }
	private static int learned(UUID character) throws Exception { return number("SELECT COUNT(*) FROM dalmaru.character_learned_recipes WHERE character_id=?", character); }
	private static int researchRows(UUID character) throws Exception { return number("SELECT COUNT(*) FROM dalmaru.character_craft_research WHERE character_id=?", character); }
	private static int equipment(UUID character) throws Exception { return number("SELECT COUNT(*) FROM dalmaru.character_equipment WHERE character_id=? AND item_id='gear.iron_blade'", character); }
	private static int enhancement(UUID character) throws Exception { return number("SELECT enhancement FROM dalmaru.character_equipment WHERE character_id=? AND item_id='gear.iron_blade'", character); }
	private static int enhancements(UUID character) throws Exception { return number("SELECT COUNT(*) FROM dalmaru.character_enhancement_requests WHERE equipment_id IN (SELECT equipment_id FROM dalmaru.character_equipment WHERE character_id=?)", character); }
	private static int orders(UUID character) throws Exception { return number("SELECT COUNT(*) FROM dalmaru.character_precision_orders WHERE character_id=?", character); }
	private static String equipmentBinding(UUID character) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT binding FROM dalmaru.character_equipment WHERE character_id=? AND item_id='gear.iron_blade'")) {
			statement.setObject(1, character);
			try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
		}
	}

	private static int number(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
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
				"DELETE FROM dalmaru.character_enhancement_requests WHERE equipment_id IN (SELECT equipment_id FROM dalmaru.character_equipment WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?))",
				"DELETE FROM dalmaru.character_equipment WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_precision_orders WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_learned_recipes WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_craft_research WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_life_professions WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.characters WHERE account_id=?",
				"DELETE FROM dalmaru.accounts WHERE account_id=?"
			}) try (var statement = connection.prepareStatement(query)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); }
		}
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
