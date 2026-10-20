package kr.dalmaru;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.craft.CraftOrderRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5CraftOrdersCheck {
	private static final UUID REQUESTER = UUID.fromString("00000000-0000-4000-8000-00000000a549");
	private static final UUID CRAFTER = UUID.fromString("00000000-0000-4000-8000-00000000a54a");
	private static UUID requesterCharacter;
	private static UUID crafterCharacter;
	private P5CraftOrdersCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		String repo = Files.readString(Path.of("src/main/java/kr/dalmaru/craft/CraftOrderRepository.java"));
		String village = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruVillageScreen.java"));
		String screen = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruCraftOrderScreen.java"));
		int locked = repo.indexOf("lockAccounts(connection, accountId, other)");
		int journal = repo.indexOf("RequestJournal.begin");
		require(locked >= 0 && locked < journal && repo.contains("left.compareTo(right)") && repo.contains("ORDER_FEE") && repo.contains("SupplyStay.refuseMove") && !repo.contains("character_mailbox") && !repo.contains("trade_offers") && !repo.contains("character_enhancement") && village.contains("주문판") && screen.contains("의뢰"), "Craft orders changed mail, trade, or the lock order.");
		FixtureMigrations.apply();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var orders = new CraftOrderRepository();
		try {
			long requesterGeneration = accounts.openSession(REQUESTER).connectionGeneration();
			long crafterGeneration = accounts.openSession(CRAFTER).connectionGeneration();
			UUID requester = requesterCharacter = characters.create(REQUESTER, 1, "달마루_의뢰").characterId();
			UUID crafter = crafterCharacter = characters.create(CRAFTER, 1, "달마루_제작").characterId();
			characters.selectActive(REQUESTER, requester);
			characters.selectActive(CRAFTER, crafter);
			wallet(requester, 400);
			wallet(crafter, 0);
			int gearBase = equipment(requester);
			sql("INSERT INTO dalmaru.character_life_professions(character_id, job, proficiency) VALUES(?, 'blacksmith', 20)", crafter);
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'CHARACTER')", requester);
			reject(() -> place(orders, requesterGeneration, 1, requester, 25, "CHARACTER"));
			require(material(requester) == 1 && orders(requester) == 0, "A bound supply stack was taken.");
			sql("DELETE FROM dalmaru.character_materials WHERE character_id=?", requester);
			wallet(requester, 10);
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'FREE')", requester);
			reject(() -> place(orders, requesterGeneration, 2, requester, 25, "FREE"));
			require(walletOf(requester) == 10 && material(requester) == 1 && orders(requester) == 0, "A short wallet locked an order.");
			wallet(requester, 400);
			var opened = place(orders, requesterGeneration, 3, requester, 25, "FREE");
			var replay = place(orders, requesterGeneration, 3, requester, 25, "FREE");
			UUID orderId = UUID.fromString(opened.result().get("orderId").getAsString());
			require(!opened.replayed() && replay.replayed() && orderId.equals(UUID.fromString(replay.result().get("orderId").getAsString())) && walletOf(requester) == 375 && material(requester) == 0 && escrow(orderId) == 1 && "OPEN".equals(state(orderId)), "Place did not lock one order.");
			reject(() -> accept(orders, crafterGeneration, 4, crafter, orderId, 16, 64, 16));
			sql("UPDATE dalmaru.character_life_professions SET proficiency=21 WHERE character_id=?", crafter);
			reject(() -> accept(orders, crafterGeneration, 5, crafter, orderId, 16, 64, 16));
			sql("INSERT INTO dalmaru.character_learned_recipes(character_id, recipe_id) VALUES(?, 'craft.iron_blade')", crafter);
			reject(() -> accept(orders, crafterGeneration, 6, crafter, orderId, 1000, 64, 0));
			reject(() -> accept(orders, requesterGeneration, 7, requester, orderId, 16, 64, 16));
			require("OPEN".equals(state(orderId)) && escrow(orderId) == 1, "A refused accept changed the order.");
			var accepted = accept(orders, crafterGeneration, 8, crafter, orderId, 16, 64, 16);
			var acceptedReplay = accept(orders, crafterGeneration, 8, crafter, orderId, 16, 64, 16);
			require(!accepted.replayed() && acceptedReplay.replayed() && "ACCEPTED".equals(state(orderId)) && "OK".equals(accepted.result().get("status").getAsString()), "Accept did not store one crafter.");
			reject(() -> accept(orders, crafterGeneration, 9, crafter, orderId, 16, 64, 16));
			var settled = settle(orders, crafterGeneration, 10, crafter, orderId, 16, 64, 16);
			var settledReplay = settle(orders, crafterGeneration, 10, crafter, orderId, 16, 64, 16);
			UUID gear = UUID.fromString(settled.result().get("equipmentId").getAsString());
			require(!settled.replayed() && settledReplay.replayed() && settled.result().get("fee").getAsInt() == 1 && "FREE".equals(settled.result().get("binding").getAsString()) && gear.equals(UUID.fromString(settledReplay.result().get("equipmentId").getAsString())) && walletOf(requester) == 375 && walletOf(crafter) == 24 && "FREE".equals(gearBinding(gear)) && escrow(orderId) == 0 && "SETTLED".equals(state(orderId)) && feeSum(crafter) == -1 && ledger(crafter) == 24 && ledger(requester) == -25, "The 25-copper order did not pay 24 and record a 1-copper fee.");
			reject(() -> settle(orders, crafterGeneration, 11, crafter, orderId, 16, 64, 16));
			reject(() -> cancel(orders, requesterGeneration, 12, requester, orderId));
			require(equipment(requester) == gearBase + 1, "A second settle minted another piece.");
			giveMaterial(requester, "FREE");
			UUID second = placeAndAccept(orders, requesterGeneration, crafterGeneration, requester, crafter, 13, 24, "FREE");
			var noFee = settle(orders, crafterGeneration, 15, crafter, second, 16, 64, 16);
			require(noFee.result().get("fee").getAsInt() == 0 && walletOf(crafter) == 48 && walletOf(requester) == 351 && feeSum(crafter) == -1 && ledger(crafter) == 48, "A 24-copper order charged a fee.");
			giveMaterial(requester, "FREE");
			UUID third = placeAndAccept(orders, requesterGeneration, crafterGeneration, requester, crafter, 16, 100, "FREE");
			var hundred = settle(orders, crafterGeneration, 18, crafter, third, 16, 64, 16);
			require(hundred.result().get("fee").getAsInt() == 2 && walletOf(requester) == 251 && walletOf(crafter) == 146 && feeSum(crafter) == -3 && ledger(crafter) == walletOf(crafter) && ledger(requester) + 400 == walletOf(requester), "A 100-copper order did not keep the wallet and the ledger together.");
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'ACCOUNT')", requester);
			UUID bound = placeAndAccept(orders, requesterGeneration, crafterGeneration, requester, crafter, 19, 25, "ACCOUNT");
			var stronger = settle(orders, crafterGeneration, 21, crafter, bound, 16, 64, 16);
			require("ACCOUNT".equals(stronger.result().get("binding").getAsString()) && "ACCOUNT".equals(gearBinding(UUID.fromString(stronger.result().get("equipmentId").getAsString()))) && stronger.result().get("fee").getAsInt() == 1, "The result binding was weaker than the locked material.");
			int gears = equipment(requester);
			giveMaterial(requester, "FREE");
			UUID expiring = placeAndAccept(orders, requesterGeneration, crafterGeneration, requester, crafter, 22, 25, "FREE");
			sql("UPDATE dalmaru.character_craft_orders SET expires_at=clock_timestamp() WHERE order_id=?", expiring);
			var expired = settle(orders, crafterGeneration, 24, crafter, expiring, 16, 64, 16);
			require("EXPIRED".equals(expired.result().get("status").getAsString()) && "EXPIRED".equals(state(expiring)) && material(requester) == 1 && escrow(expiring) == 0 && equipment(requester) == gears, "The 24-hour boundary kept the locked goods.");
			giveMaterial(requester, "FREE");
			UUID live = placeAndAccept(orders, requesterGeneration, crafterGeneration, requester, crafter, 25, 25, "FREE");
			sql("UPDATE dalmaru.character_craft_orders SET expires_at=clock_timestamp() + interval '1 minute' WHERE order_id=?", live);
			var still = settle(orders, crafterGeneration, 27, crafter, live, 16, 64, 16);
			require("OK".equals(still.result().get("status").getAsString()) && still.result().get("fee").getAsInt() == 1 && "SETTLED".equals(state(live)) && material(requester) == 0, "An order inside the 24 hours was returned.");
			int copper = walletOf(requester);
			giveMaterial(requester, "FREE");
			UUID unready = placeAndAccept(orders, requesterGeneration, crafterGeneration, requester, crafter, 28, 25, "FREE");
			sql("DELETE FROM dalmaru.character_learned_recipes WHERE character_id=?", crafter);
			var returned = settle(orders, crafterGeneration, 30, crafter, unready, 16, 64, 16);
			require("NOT_READY".equals(returned.result().get("status").getAsString()) && "CANCELLED".equals(state(unready)) && material(requester) == 1 && walletOf(requester) == copper, "A missed craft condition kept the locked goods.");
			sql("INSERT INTO dalmaru.character_learned_recipes(character_id, recipe_id) VALUES(?, 'craft.iron_blade')", crafter);
			giveMaterial(requester, "FREE");
			int beforeCancel = walletOf(requester);
			UUID cancelling = UUID.fromString(place(orders, requesterGeneration, 31, requester, 25, "FREE").result().get("orderId").getAsString());
			require(walletOf(requester) == beforeCancel - 25, "Cancel setup charged twice.");
			raceCancel(orders, requesterGeneration, requester, cancelling);
			require(material(requester) == 1 && walletOf(requester) == beforeCancel && "CANCELLED".equals(state(cancelling)), "Concurrent cancel returned the goods twice.");
			giveMaterial(requester, "FREE");
			UUID contested = UUID.fromString(place(orders, requesterGeneration, 40, requester, 25, "FREE").result().get("orderId").getAsString());
			raceAccept(orders, crafterGeneration, crafter, contested);
			require("ACCEPTED".equals(state(contested)), "Concurrent accept stored more than one crafter.");
			int beforeGear = equipment(requester);
			int beforeCraft = walletOf(crafter);
			raceSettle(orders, crafterGeneration, crafter, contested);
			require(equipment(requester) == beforeGear + 1 && walletOf(crafter) == beforeCraft + 24 && "SETTLED".equals(state(contested)), "Concurrent settle minted or paid twice.");
			giveMaterial(requester, "FREE");
			sql("UPDATE dalmaru.character_materials SET quantity=2 WHERE character_id=? AND material_id='material.iron_ingot'", requester);
			UUID left = placeAndAccept(orders, requesterGeneration, crafterGeneration, requester, crafter, 50, 25, "FREE");
			sql("UPDATE dalmaru.character_materials SET quantity=1 WHERE character_id=? AND material_id='material.iron_ingot'", requester);
			UUID right = placeAndAccept(orders, requesterGeneration, crafterGeneration, requester, crafter, 52, 25, "FREE");
			racePair(orders, crafterGeneration, crafter, left, right);
			require("SETTLED".equals(state(left)) && "SETTLED".equals(state(right)) && equipment(requester) == beforeGear + 3, "Two orders on the same accounts did not both settle.");
			giveMaterial(requester, "FREE");
			int heldCopper = walletOf(requester);
			int heldGear = equipment(requester);
			int heldCraft = walletOf(crafter);
			UUID blocked = placeAndAccept(orders, requesterGeneration, crafterGeneration, requester, crafter, 60, 25, "FREE");
			wallet(crafter, Integer.MAX_VALUE);
			boolean failed = false;
			try { settle(orders, crafterGeneration, 62, crafter, blocked, 16, 64, 16); }
			catch (java.sql.SQLException expected) { failed = true; }
			require(failed && "ACCEPTED".equals(state(blocked)) && escrow(blocked) == 1 && equipment(requester) == heldGear && walletOf(requester) == heldCopper - 25, "A payout failure kept a partial settlement.");
			wallet(crafter, heldCraft);
			var recovered = settle(orders, crafterGeneration, 63, crafter, blocked, 16, 64, 16);
			require("OK".equals(recovered.result().get("status").getAsString()) && recovered.result().get("fee").getAsInt() == 1 && walletOf(crafter) == heldCraft + 24 && escrow(blocked) == 0 && equipment(requester) == heldGear + 1, "The order did not settle after the payout failure rolled back.");
			System.out.println("Craft orders lock both accounts, charge 2 percent once, and return the rest PASS");
		} finally {
			cleanup();
		}
	}

	private static UUID placeAndAccept(CraftOrderRepository orders, long requesterGeneration, long crafterGeneration, UUID requester, UUID crafter, long request, int copper, String binding) throws Exception {
		var placed = place(orders, requesterGeneration, request, requester, copper, binding);
		UUID orderId = UUID.fromString(placed.result().get("orderId").getAsString());
		accept(orders, crafterGeneration, request + 1, crafter, orderId, 16, 64, 16);
		return orderId;
	}

	private static void raceCancel(CraftOrderRepository orders, long generation, UUID requester, UUID orderId) throws Exception {
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(2);
		var ok = new AtomicInteger();
		var failure = new AtomicReference<Throwable>();
		for (int i = 0; i < 2; i++) {
			long request = 70 + i;
			new Thread(() -> {
				try { start.await(); cancel(orders, generation, request, requester, orderId); ok.incrementAndGet(); }
				catch (IllegalArgumentException expected) { }
				catch (Throwable error) { failure.set(error); }
				finally { done.countDown(); }
			}).start();
		}
		start.countDown();
		if (!done.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Cancel wait expired.");
		if (failure.get() != null) throw new IllegalStateException(failure.get());
		require(ok.get() == 1, "Cancel did not have one winner.");
	}

	private static void raceAccept(CraftOrderRepository orders, long generation, UUID crafter, UUID orderId) throws Exception {
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(2);
		var ok = new AtomicInteger();
		var failure = new AtomicReference<Throwable>();
		for (int i = 0; i < 2; i++) {
			long request = 80 + i;
			new Thread(() -> {
				try { start.await(); accept(orders, generation, request, crafter, orderId, 16, 64, 16); ok.incrementAndGet(); }
				catch (IllegalArgumentException expected) { }
				catch (Throwable error) { failure.set(error); }
				finally { done.countDown(); }
			}).start();
		}
		start.countDown();
		if (!done.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Accept wait expired.");
		if (failure.get() != null) throw new IllegalStateException(failure.get());
		require(ok.get() == 1, "Accept did not have one winner.");
	}

	private static void raceSettle(CraftOrderRepository orders, long generation, UUID crafter, UUID orderId) throws Exception {
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(2);
		var ok = new AtomicInteger();
		var failure = new AtomicReference<Throwable>();
		for (int i = 0; i < 2; i++) {
			long request = 90 + i;
			new Thread(() -> {
				try { start.await(); settle(orders, generation, request, crafter, orderId, 16, 64, 16); ok.incrementAndGet(); }
				catch (IllegalArgumentException expected) { }
				catch (Throwable error) { failure.set(error); }
				finally { done.countDown(); }
			}).start();
		}
		start.countDown();
		if (!done.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Settle wait expired.");
		if (failure.get() != null) throw new IllegalStateException(failure.get());
		require(ok.get() == 1, "Settle did not have one winner.");
	}

	private static void racePair(CraftOrderRepository orders, long generation, UUID crafter, UUID left, UUID right) throws Exception {
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(2);
		var failure = new AtomicReference<Throwable>();
		UUID[] ids = { left, right };
		for (int i = 0; i < 2; i++) {
			UUID orderId = ids[i];
			long request = 100 + i;
			new Thread(() -> {
				try { start.await(); settle(orders, generation, request, crafter, orderId, 16, 64, 16); }
				catch (Throwable error) { failure.set(error); }
				finally { done.countDown(); }
			}).start();
		}
		start.countDown();
		if (!done.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Account lock wait expired.");
		if (failure.get() != null) throw new IllegalStateException(failure.get());
	}

	private static CraftOrderRepository.Journaled place(CraftOrderRepository orders, long generation, long request, UUID character, int copper, String binding) throws Exception {
		var body = new JsonObject();
		var materials = new com.google.gson.JsonArray();
		var line = new JsonObject();
		line.addProperty("materialId", "material.iron_ingot");
		line.addProperty("binding", binding);
		line.addProperty("quantity", 1);
		materials.add(line);
		body.addProperty("recipeId", "craft.iron_blade");
		body.add("materials", materials);
		body.addProperty("copper", copper);
		UUID account = characterAccount(character);
		return orders.placeJournaled(account, generation, request, character, "craft.iron_blade", copper, RequestEnvelope.legacy(account, generation, character, "place_craft_order", request, body));
	}

	private static CraftOrderRepository.Journaled accept(CraftOrderRepository orders, long generation, long request, UUID character, UUID orderId, int x, int y, int z) throws Exception {
		var body = new JsonObject();
		body.addProperty("orderId", orderId.toString());
		UUID account = characterAccount(character);
		return orders.acceptJournaled(account, generation, request, character, orderId, "minecraft:overworld", x, y, z, RequestEnvelope.legacy(account, generation, character, "accept_craft_order", request, body));
	}

	private static CraftOrderRepository.Journaled settle(CraftOrderRepository orders, long generation, long request, UUID character, UUID orderId, int x, int y, int z) throws Exception {
		var body = new JsonObject();
		body.addProperty("orderId", orderId.toString());
		UUID account = characterAccount(character);
		return orders.settleJournaled(account, generation, request, character, orderId, "minecraft:overworld", x, y, z, RequestEnvelope.legacy(account, generation, character, "settle_craft_order", request, body));
	}

	private static CraftOrderRepository.Journaled cancel(CraftOrderRepository orders, long generation, long request, UUID character, UUID orderId) throws Exception {
		var body = new JsonObject();
		body.addProperty("orderId", orderId.toString());
		UUID account = characterAccount(character);
		return orders.cancelJournaled(account, generation, request, character, orderId, RequestEnvelope.legacy(account, generation, character, "cancel_craft_order", request, body));
	}

	private static UUID characterAccount(UUID character) {
		if (character.equals(requesterCharacter)) return REQUESTER;
		if (character.equals(crafterCharacter)) return CRAFTER;
		throw new IllegalStateException("Missing character.");
	}

	private static void giveMaterial(UUID character, String binding) throws Exception {
		sql("DELETE FROM dalmaru.character_materials WHERE character_id=?", character);
		try (var connection = connection(); var statement = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, ?)")) {
			statement.setObject(1, character);
			statement.setString(2, binding);
			statement.executeUpdate();
		}
	}

	private static int material(UUID character) throws Exception { return number("SELECT COALESCE(SUM(quantity),0) FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.iron_ingot'", character); }
	private static int orders(UUID character) throws Exception { return number("SELECT COUNT(*) FROM dalmaru.character_craft_orders WHERE requester_character_id=?", character); }
	private static int escrow(UUID orderId) throws Exception { return number("SELECT COALESCE(SUM(quantity),0) FROM dalmaru.character_craft_order_materials WHERE order_id=?", orderId); }
	private static int equipment(UUID character) throws Exception { return number("SELECT COUNT(*) FROM dalmaru.character_equipment WHERE character_id=?", character); }
	private static int walletOf(UUID character) throws Exception { return number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", character); }
	private static int ledger(UUID character) throws Exception { return number("SELECT COALESCE(SUM(copper_delta),0) FROM dalmaru.character_currency_ledger WHERE character_id=?", character); }
	private static int feeSum(UUID character) throws Exception { return number("SELECT COALESCE(SUM(copper_delta),0) FROM dalmaru.character_currency_ledger WHERE character_id=? AND reason='ORDER_FEE'", character); }

	private static String state(UUID orderId) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT state FROM dalmaru.character_craft_orders WHERE order_id=?")) {
			statement.setObject(1, orderId);
			try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
		}
	}

	private static String gearBinding(UUID equipment) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT binding FROM dalmaru.character_equipment WHERE equipment_id=?")) {
			statement.setObject(1, equipment);
			try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
		}
	}

	private static int number(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
		}
	}

	private static void wallet(UUID character, int copper) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("INSERT INTO dalmaru.character_wallet(character_id, copper) VALUES(?, ?) ON CONFLICT(character_id) DO UPDATE SET copper=EXCLUDED.copper")) {
			statement.setObject(1, character);
			statement.setInt(2, copper);
			statement.executeUpdate();
		}
	}

	private static void sql(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) { statement.setObject(1, id); statement.executeUpdate(); }
	}

	private static java.sql.Connection connection() throws Exception {
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup() throws Exception {
		for (UUID account : new UUID[] { REQUESTER, CRAFTER }) {
			try (var connection = connection()) {
				for (String query : new String[] {
					"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
					"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_craft_orders WHERE requester_account_id=? OR crafter_account_id=?",
					"DELETE FROM dalmaru.character_learned_recipes WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_equipment WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_life_professions WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_currency_ledger WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.characters WHERE account_id=?",
					"DELETE FROM dalmaru.accounts WHERE account_id=?"
				}) try (var statement = connection.prepareStatement(query)) {
					statement.setObject(1, account);
					if (query.contains("OR crafter_account_id")) statement.setObject(2, account);
					statement.executeUpdate();
				}
			}
		}
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Craft order was accepted.");
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
