package kr.dalmaru;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.market.MarketRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5MarketExchangeCheck {
	public static void main(String[] args) throws Exception {
		String market = Files.readString(Path.of("src/main/java/kr/dalmaru/market/MarketRepository.java"));
		String village = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruVillageScreen.java"));
		String orders = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruCraftOrderScreen.java"));
		check(!market.contains("character_craft_orders") && village.contains("주문판") && village.contains("DalmaruCraftOrderScreen") && village.contains("거래소") && orders.contains("requestCraftOrderSettle"), "The order board no longer settles through craft orders.");
		FixtureDatabase.databaseName();
		FixtureMigrations.apply();
		UUID sellerId = UUID.randomUUID();
		UUID buyerId = UUID.randomUUID();
		try {
			var accounts = new AccountRepository();
			var characters = new CharacterRepository();
			var seller = person(accounts, characters, sellerId, "ex");
			var buyer = person(accounts, characters, buyerId, "ey");
			var marketRepo = new MarketRepository();
			stock(seller.character, 4);
			wallet(seller.character, 0);
			wallet(buyer.character, 200);
			UUID staleStash = stash(marketRepo, seller);
			UUID staleListing = list(marketRepo, seller, staleStash, "EXCHANGE", 100);
			int requests = requests(seller.account);
			var found = marketRepo.search("EXCHANGE", "iron_ingot", 0);
			var limited = marketRepo.search("EXCHANGE", "iron_ingot", -1);
			check(requests(seller.account) == requests && "OK".equals(found.get("status").getAsString()) && seen(found, staleListing, 100) && "FIELD_LIMIT".equals(limited.get("status").getAsString()), "Search wrote a journal or missed the listing.");
			price(staleListing, 80);
			var old = marketRepo.buyJournaled(buyer.account, buyer.generation, buyer.take(), buyer.character, buy(buyer, staleListing, 100));
			check("CONFLICT".equals(status(old)) && copper(buyer.character) == 200 && fees(seller.character) == 0, "A stale search price still bought the listing.");
			var purchase = buy(buyer, staleListing, 80);
			long purchaseId = buyer.take();
			var bought = marketRepo.buyJournaled(buyer.account, buyer.generation, purchaseId, buyer.character, purchase);
			var replay = marketRepo.buyJournaled(buyer.account, buyer.generation, purchaseId, buyer.character, purchase);
			check("OK".equals(status(bought)) && bought.result().get("fee").getAsInt() == 4 && replay.replayed() && copper(seller.character) == 76 && copper(buyer.character) == 120 && fees(seller.character) == -4, "The 80 copper exchange sale did not round the fee to 4 once.");
			UUID listed = list(marketRepo, seller, stash(marketRepo, seller), "EXCHANGE", 100);
			var full = marketRepo.buyJournaled(buyer.account, buyer.generation, buyer.take(), buyer.character, buy(buyer, listed, 100));
			check("OK".equals(status(full)) && full.result().get("fee").getAsInt() == 5 && copper(seller.character) == 171 && fees(seller.character) == -9 && orders(seller.character) == 0, "A 100 copper exchange sale did not record a 5 copper fee once.");
			UUID returnedStash = stash(marketRepo, seller);
			UUID returned = list(marketRepo, seller, returnedStash, "EXCHANGE", 50);
			int feeNow = fees(seller.character);
			var cancelled = marketRepo.cancelJournaled(seller.account, seller.generation, seller.take(), seller.character, cancel(seller, returned));
			var claimed = marketRepo.claimJournaled(seller.account, seller.generation, seller.take(), seller.character, claim(seller, returnedStash));
			check("OK".equals(status(cancelled)) && "STASH".equals(cancelled.result().get("location").getAsString()) && "OK".equals(status(claimed)) && fees(seller.character) == feeNow && quantity(seller.character) == 2, "Cancel did not return the exchange goods without a fee.");
			check(race(marketRepo, seller, stash(marketRepo, seller)), "One material lock was listed on both channels.");
			UUID gear = UUID.randomUUID();
			equipment(seller.character, gear);
			var stored = marketRepo.stashJournaled(seller.account, seller.generation, seller.take(), seller.character, equipmentStash(seller, gear));
			check("OK".equals(status(stored)) && race(marketRepo, seller, UUID.fromString(stored.result().get("stashId").getAsString())), "One equipment lock was listed on both channels.");
			System.out.println("PASS exchange 5 percent, channel lock, stale search, refund, and one order board");
		} finally { cleanup(sellerId); cleanup(buyerId); }
	}

	private static boolean race(MarketRepository market, Person seller, UUID stash) throws Exception {
		var stall = listBody(seller, stash, "STALL", 40);
		var exchange = listBody(seller, stash, "EXCHANGE", 40);
		long left = seller.take();
		long right = seller.take();
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			var first = pool.submit(() -> { start.await(); return new MarketRepository().listJournaled(seller.account, seller.generation, left, seller.character, stall); });
			var second = pool.submit(() -> { start.await(); return new MarketRepository().listJournaled(seller.account, seller.generation, right, seller.character, exchange); });
			start.countDown();
			String sa = status(first.get(10, TimeUnit.SECONDS));
			String sb = status(second.get(10, TimeUnit.SECONDS));
			return ((sa.equals("OK") && sb.equals("CHANNEL_BUSY")) || (sa.equals("CHANNEL_BUSY") && sb.equals("OK"))) && listings(stash) == 1;
		} finally { pool.shutdownNow(); }
	}

	private static boolean seen(JsonObject result, UUID listing, int price) {
		for (var row : result.getAsJsonArray("rows")) {
			var line = row.getAsJsonObject();
			if (listing.toString().equals(line.get("listingId").getAsString()) && line.get("price").getAsInt() == price) return true;
		}
		return false;
	}

	private static UUID stash(MarketRepository market, Person seller) throws Exception {
		var stored = market.stashJournaled(seller.account, seller.generation, seller.take(), seller.character, materialStash(seller));
		if (!"OK".equals(status(stored))) throw new AssertionError("Stash failed.");
		return UUID.fromString(stored.result().get("stashId").getAsString());
	}

	private static UUID list(MarketRepository market, Person seller, UUID stash, String channel, int price) throws Exception {
		var listed = market.listJournaled(seller.account, seller.generation, seller.take(), seller.character, listBody(seller, stash, channel, price));
		if (!"OK".equals(status(listed))) throw new AssertionError("List failed.");
		return UUID.fromString(listed.result().get("listingId").getAsString());
	}

	private static String status(MarketRepository.Journaled result) { return result.result().get("status").getAsString(); }

	private static RequestEnvelope materialStash(Person person) {
		var body = new JsonObject();
		body.addProperty("materialId", "material.iron_ingot");
		body.addProperty("quantity", 1);
		return new RequestEnvelope(UUID.randomUUID(), person.generation, person.character, "stash_market", body);
	}

	private static RequestEnvelope equipmentStash(Person person, UUID equipment) {
		var body = new JsonObject();
		body.addProperty("equipmentId", equipment.toString());
		return new RequestEnvelope(UUID.randomUUID(), person.generation, person.character, "stash_market", body);
	}

	private static RequestEnvelope listBody(Person person, UUID stash, String channel, int price) {
		var body = new JsonObject();
		body.addProperty("stashId", stash.toString());
		body.addProperty("channel", channel);
		body.addProperty("price", price);
		return new RequestEnvelope(UUID.randomUUID(), person.generation, person.character, "list_market", body);
	}

	private static RequestEnvelope buy(Person person, UUID listing, int price) {
		var body = new JsonObject();
		body.addProperty("listingId", listing.toString());
		body.addProperty("price", price);
		return new RequestEnvelope(UUID.randomUUID(), person.generation, person.character, "buy_market", body);
	}

	private static RequestEnvelope cancel(Person person, UUID listing) {
		var body = new JsonObject();
		body.addProperty("listingId", listing.toString());
		return new RequestEnvelope(UUID.randomUUID(), person.generation, person.character, "cancel_market", body);
	}

	private static RequestEnvelope claim(Person person, UUID stash) {
		var body = new JsonObject();
		body.addProperty("stashId", stash.toString());
		return new RequestEnvelope(UUID.randomUUID(), person.generation, person.character, "claim_market", body);
	}

	private static Person person(AccountRepository accounts, CharacterRepository characters, UUID account, String prefix) throws Exception {
		long generation = accounts.openSession(account).connectionGeneration();
		UUID character = characters.create(account, generation, 1, 1, prefix + account.toString().substring(0, 8)).character().characterId();
		characters.selectActive(account, generation, 2, character);
		return new Person(account, generation, character);
	}

	private static void stock(UUID character, int quantity) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', ?, 'FREE')")) {
			update.setObject(1, character);
			update.setInt(2, quantity);
			update.executeUpdate();
		}
	}

	private static void equipment(UUID character, UUID equipment) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("INSERT INTO dalmaru.character_equipment(equipment_id, character_id, slot_id, item_id, required_level, bound, location, binding, enhancement) VALUES(?, ?, NULL, 'material.iron_ingot', 1, FALSE, 'INVENTORY', 'FREE', 0)")) {
			update.setObject(1, equipment);
			update.setObject(2, character);
			update.executeUpdate();
		}
	}

	private static void wallet(UUID character, int copper) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("INSERT INTO dalmaru.character_wallet(character_id, copper) VALUES(?, ?) ON CONFLICT(character_id) DO UPDATE SET copper=EXCLUDED.copper")) {
			update.setObject(1, character);
			update.setInt(2, copper);
			update.executeUpdate();
		}
	}

	private static void price(UUID listing, int price) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("UPDATE dalmaru.market_listings SET price=? WHERE listing_id=?")) {
			update.setInt(1, price);
			update.setObject(2, listing);
			if (update.executeUpdate() != 1) throw new SQLException("Exchange price was not moved.");
		}
	}

	private static int copper(UUID character) throws SQLException { return number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", character); }

	private static int fees(UUID character) throws SQLException { return number("SELECT coalesce(sum(copper_delta), 0) FROM dalmaru.character_currency_ledger WHERE character_id=? AND reason='EXCHANGE_FEE'", character); }

	private static int quantity(UUID character) throws SQLException { return number("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.iron_ingot'", character); }

	private static int requests(UUID account) throws SQLException { return number("SELECT count(*) FROM dalmaru.operation_requests WHERE account_id=?", account); }

	private static int orders(UUID character) throws SQLException { return number("SELECT count(*) FROM dalmaru.character_craft_orders WHERE requester_character_id=?", character); }

	private static int listings(UUID stash) throws SQLException { return number("SELECT count(*) FROM dalmaru.market_listings WHERE stash_id=?", stash); }

	private static int number(String sql, UUID id) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement(sql)) {
			query.setObject(1, id);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private static java.sql.Connection connection() throws SQLException {
		return DriverManager.getConnection("jdbc:postgresql://localhost:15432/" + FixtureDatabase.databaseName(), "postgres", System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup(UUID account) throws SQLException {
		try (var connection = connection()) {
			connection.setAutoCommit(false);
			try {
				for (String sql : java.util.List.of(
					"DELETE FROM dalmaru.market_listings WHERE seller_account=?",
					"DELETE FROM dalmaru.market_stashes WHERE account_id=?",
					"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
					"DELETE FROM dalmaru.character_selection_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_creation_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.characters WHERE account_id=?",
					"DELETE FROM dalmaru.accounts WHERE account_id=?")) {
					try (var query = connection.prepareStatement(sql)) { query.setObject(1, account); query.executeUpdate(); }
				}
				connection.commit();
			} catch (SQLException exception) { connection.rollback(); throw exception; }
		}
	}

	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

	private static final class Person {
		final UUID account;
		final long generation;
		final UUID character;
		long correlation = 3;

		Person(UUID account, long generation, UUID character) {
			this.account = account;
			this.generation = generation;
			this.character = character;
		}

		long take() { return correlation++; }
	}
}
