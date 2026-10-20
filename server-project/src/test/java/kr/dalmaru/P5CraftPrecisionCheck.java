package kr.dalmaru;

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
import kr.dalmaru.craft.PrecisionOrderRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5CraftPrecisionCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a548");
	private P5CraftPrecisionCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		require(CraftCatalog.recipe("craft.iron_ingot").output().itemId().equals("material.iron_ingot") && CraftCatalog.precision("craft.precision_blade").steps().size() == 2, "Precision content changed a basic recipe.");
		String repo = Files.readString(Path.of("src/main/java/kr/dalmaru/craft/PrecisionOrderRepository.java"));
		String craft = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruCraftScreen.java"));
		String screen = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruPrecisionScreen.java"));
		require(!repo.contains("character_enhancement") && !repo.contains("refinement") && craft.contains("정밀 주문") && screen.contains("가열"), "Precision order changed an enhancement ledger or lost its screen.");
		FixtureMigrations.apply();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var orders = new PrecisionOrderRepository();
		try {
			long generation = accounts.openSession(ACCOUNT).connectionGeneration();
			UUID character = characters.create(ACCOUNT, 1, "달마루_정밀").characterId();
			characters.selectActive(ACCOUNT, character);
			sql("INSERT INTO dalmaru.character_life_professions(character_id, job, proficiency) VALUES(?, 'blacksmith', 20)", character);
			sql("INSERT INTO dalmaru.character_learned_recipes(character_id, recipe_id) VALUES(?, 'craft.precision_blade')", character);
			UUID broken = gear(character);
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'FREE')", character);
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.basic_fitting', 1, 'FREE')", character);
			String before = gearText(broken);
			reject(() -> begin(orders, generation, 1, character, broken, 16, 64, 16));
			sql("UPDATE dalmaru.character_life_professions SET proficiency=21 WHERE character_id=? AND job='blacksmith'", character);
			reject(() -> begin(orders, generation, 2, character, broken, 1000, 64, 0));
			reject(() -> begin(orders, generation, 3, character, UUID.randomUUID(), 16, 64, 16));
			var opened = begin(orders, generation, 4, character, broken, 16, 64, 16);
			var replay = begin(orders, generation, 4, character, broken, 16, 64, 16);
			UUID orderId = UUID.fromString(opened.result().get("orderId").getAsString());
			require(!opened.replayed() && replay.replayed() && opened.result().get("step").getAsInt() == 0 && orderId.equals(UUID.fromString(replay.result().get("orderId").getAsString())) && count(character) == 1, "Begin did not store one order.");
			reject(() -> step(orders, generation, 5, character, orderId, 9, "heat"));
			var failed = step(orders, generation, 6, character, orderId, 0, "cool");
			require(!failed.replayed() && !failed.result().get("succeeded").getAsBoolean() && material(character, "material.iron_ingot") == 1 && material(character, "material.basic_fitting") == 1 && before.equals(gearText(broken)) && "FAILED".equals(state(orderId)) && stepIndex(orderId) == 0, "A failed step spent material or changed the equipment.");
			UUID blade = gear(character);
			String kept = gearText(blade);
			var second = begin(orders, generation, 7, character, blade, 16, 64, 16);
			UUID live = UUID.fromString(second.result().get("orderId").getAsString());
			var heated = step(orders, generation, 8, character, live, 0, "heat");
			var heatedReplay = step(orders, generation, 8, character, live, 0, "heat");
			require(!heated.replayed() && heatedReplay.replayed() && heated.result().get("step").getAsInt() == 1 && !heated.result().get("succeeded").getAsBoolean() && material(character, "material.iron_ingot") == 0 && material(character, "material.basic_fitting") == 1 && kept.equals(gearText(blade)), "The first step was not applied once.");
			long reconnected = accounts.openSession(ACCOUNT).connectionGeneration();
			characters.selectActive(ACCOUNT, character);
			var quenched = step(orders, reconnected, 9, character, live, 1, "quench");
			require(quenched.result().get("succeeded").getAsBoolean() && blade.equals(UUID.fromString(quenched.result().get("equipmentId").getAsString())) && kept.equals(gearText(blade)) && material(character, "material.basic_fitting") == 0 && "DONE".equals(state(live)), "The finished order did not keep the original equipment.");
			reject(() -> step(orders, reconnected, 10, character, live, 1, "quench"));
			require(kept.equals(gearText(blade)) && enhancements(character) == 0, "A retry wrote an enhancement or another piece.");
			UUID held = gear(character);
			String heldText = gearText(held);
			var third = begin(orders, reconnected, 11, character, held, 16, 64, 16);
			UUID waiting = UUID.fromString(third.result().get("orderId").getAsString());
			reject(() -> step(orders, reconnected, 12, character, waiting, 0, "heat"));
			require(stepIndex(waiting) == 0 && "OPEN".equals(state(waiting)) && heldText.equals(gearText(held)), "A short material stack advanced the order.");
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'FREE')", character);
			race(orders, reconnected, character, waiting);
			require(material(character, "material.iron_ingot") == 0 && stepIndex(waiting) == 1 && gearText(held).startsWith("gear.iron_blade 0 "), "Concurrent steps consumed the ingot more than once.");
			System.out.println("Precision steps keep the original equipment and do not repeat a stored result PASS");
		} finally {
			cleanup();
		}
	}

	private static void race(PrecisionOrderRepository orders, long generation, UUID character, UUID orderId) throws Exception {
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(2);
		var failure = new AtomicReference<Throwable>();
		for (int i = 0; i < 2; i++) {
			long request = 20 + i;
			new Thread(() -> {
				try { start.await(); step(orders, generation, request, character, orderId, 0, "heat"); }
				catch (IllegalArgumentException expected) { }
				catch (Throwable error) { failure.set(error); }
				finally { done.countDown(); }
			}).start();
		}
		start.countDown();
		done.await();
		if (failure.get() != null) throw new IllegalStateException(failure.get());
	}

	private static PrecisionOrderRepository.Journaled begin(PrecisionOrderRepository orders, long generation, long request, UUID character, UUID equipment, int x, int y, int z) throws Exception {
		var body = new JsonObject();
		body.addProperty("recipeId", "craft.precision_blade");
		body.addProperty("equipmentId", equipment.toString());
		return orders.beginJournaled(ACCOUNT, generation, request, character, "craft.precision_blade", equipment, "minecraft:overworld", x, y, z, RequestEnvelope.legacy(ACCOUNT, generation, character, "begin_precision", request, body));
	}

	private static PrecisionOrderRepository.Journaled step(PrecisionOrderRepository orders, long generation, long request, UUID character, UUID orderId, int step, String choice) throws Exception {
		var body = new JsonObject();
		body.addProperty("orderId", orderId.toString());
		body.addProperty("stepIndex", step);
		body.addProperty("choice", choice);
		return orders.stepJournaled(ACCOUNT, generation, request, character, orderId, step, choice, "minecraft:overworld", 16, 64, 16, RequestEnvelope.legacy(ACCOUNT, generation, character, "step_precision", request, body));
	}

	private static UUID gear(UUID character) throws Exception {
		UUID id = UUID.randomUUID();
		try (var connection = connection(); var statement = connection.prepareStatement("INSERT INTO dalmaru.character_equipment(equipment_id, character_id, slot_id, item_id, required_level, bound, location, binding, enhancement) VALUES(?, ?, NULL, 'gear.iron_blade', 1, FALSE, 'INVENTORY', 'FREE', 0)")) {
			statement.setObject(1, id);
			statement.setObject(2, character);
			statement.executeUpdate();
		}
		return id;
	}

	private static String gearText(UUID equipment) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT item_id, enhancement, binding FROM dalmaru.character_equipment WHERE equipment_id=?")) {
			statement.setObject(1, equipment);
			try (var result = statement.executeQuery()) { result.next(); return result.getString(1) + " " + result.getInt(2) + " " + result.getString(3); }
		}
	}

	private static int material(UUID character, String item) throws Exception { return number("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='" + item + "'", character); }
	private static int count(UUID character) throws Exception { return number("SELECT COUNT(*) FROM dalmaru.character_precision_orders WHERE character_id=?", character); }
	private static int enhancements(UUID character) throws Exception { return number("SELECT COUNT(*) FROM dalmaru.character_enhancement_requests WHERE equipment_id IN (SELECT equipment_id FROM dalmaru.character_equipment WHERE character_id=?)", character); }
	private static int stepIndex(UUID orderId) throws Exception { return number("SELECT step_index FROM dalmaru.character_precision_orders WHERE order_id=?", orderId); }
	private static String state(UUID orderId) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT state FROM dalmaru.character_precision_orders WHERE order_id=?")) {
			statement.setObject(1, orderId);
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
				"DELETE FROM dalmaru.character_precision_orders WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_learned_recipes WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_equipment WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_life_professions WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.characters WHERE account_id=?",
				"DELETE FROM dalmaru.accounts WHERE account_id=?"
			}) try (var statement = connection.prepareStatement(query)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); }
		}
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Precision order was accepted.");
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
