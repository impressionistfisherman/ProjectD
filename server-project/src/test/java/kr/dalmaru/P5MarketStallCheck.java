package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.market.MarketRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5MarketStallCheck {
	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		FixtureMigrations.apply();
		UUID sellerId = UUID.randomUUID();
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		UUID thirdId = UUID.randomUUID();
		try {
			var accounts = new AccountRepository();
			var characters = new CharacterRepository();
			var seller = person(accounts, characters, sellerId, "ms");
			var first = person(accounts, characters, firstId, "mb");
			var second = person(accounts, characters, secondId, "mc");
			var third = person(accounts, characters, thirdId, "md");
			var market = new MarketRepository();
			wallet(seller.character, 40);
			stock(seller.character, "material.iron_ingot", 12, "FREE");
			stock(seller.character, "material.ironstone", 1, "CHARACTER");
			stock(seller.character, "supply.recovery", 1, "FREE");
			var bound = market.stashJournaled(seller.account, seller.generation, seller.take(), seller.character, stash(seller, "material.ironstone", 1));
			var stayed = market.stashJournaled(seller.account, seller.generation, seller.take(), seller.character, stash(seller, "supply.recovery", 1));
			check("BINDING_REJECTED".equals(status(bound)) && quantity(seller.character, "material.ironstone") == 1, "A bound material entered the stall.");
			check("SUPPLY_STAY".equals(status(stayed)) && quantity(seller.character, "supply.recovery") == 1, "A starter supply entered the stall.");
			var listings = new ArrayList<UUID>();
			var stashes = new ArrayList<UUID>();
			for (int i = 0; i < 10; i++) {
				var stored = market.stashJournaled(seller.account, seller.generation, seller.take(), seller.character, stash(seller, "material.iron_ingot", 1));
				UUID stashId = UUID.fromString(stored.result().get("stashId").getAsString());
				int price = i == 2 ? 125 : 100;
				var listed = market.listJournaled(seller.account, seller.generation, seller.take(), seller.character, list(seller, stashId, "STALL", price));
				listings.add(UUID.fromString(listed.result().get("listingId").getAsString()));
				stashes.add(stashId);
				if (i == 0) {
					var again = market.listJournaled(seller.account, seller.generation, seller.take(), seller.character, list(seller, stashId, "STALL", price));
					double seconds = seconds(listings.get(0));
					check("OK".equals(status(stored)) && "OK".equals(status(listed)) && "CHANNEL_BUSY".equals(status(again)) && copper(seller.character) == 40 && seconds > 7 * 86400 - 120 && seconds < 7 * 86400 + 5, "The first stall listing was not free for seven days.");
				}
			}
			var extraStored = market.stashJournaled(seller.account, seller.generation, seller.take(), seller.character, stash(seller, "material.iron_ingot", 1));
			UUID extra = UUID.fromString(extraStored.result().get("stashId").getAsString());
			var full = market.listJournaled(seller.account, seller.generation, seller.take(), seller.character, list(seller, extra, "STALL", 100));
			var exchange = market.listJournaled(seller.account, seller.generation, seller.take(), seller.character, list(seller, extra, "EXCHANGE", 100));
			check("FULL".equals(status(full)) && "FORBIDDEN".equals(status(exchange)) && "STASH".equals(location(extra)) && open(seller.character) == 10 && copper(seller.character) == 40, "An eleventh or exchange listing was accepted.");
			offline(seller.account);
			wallet(first.character, 100);
			var purchase = buy(first, listings.get(0), 100);
			var bought = market.buyJournaled(first.account, first.generation, first.take(), first.character, purchase);
			var replay = market.buyJournaled(first.account, first.generation, first.correlation - 1, first.character, purchase);
			check("OK".equals(status(bought)) && bought.result().get("fee").getAsInt() == 2 && bought.result().get("sellerCopper").getAsInt() == 138 && copper(first.character) == 0 && quantity(first.character, "material.iron_ingot") == 1 && fees(seller.character) == -2 && replay.replayed() && copper(seller.character) == 138, "An offline stall sale charged twice or used another fee.");
			characters.selectActive(seller.account, seller.generation, seller.take(), seller.character);
			wallet(second.character, 100);
			wallet(third.character, 100);
			check(race(seller, second, third, listings.get(1)) && fees(seller.character) == -4 && copper(seller.character) == 236, "Two buyers both paid for one stall listing.");
			wallet(second.character, 125);
			int before = copper(seller.character);
			var priced = market.buyJournaled(second.account, second.generation, second.take(), second.character, buy(second, listings.get(2), 125));
			check("OK".equals(status(priced)) && priced.result().get("fee").getAsInt() == 3 && copper(seller.character) == before + 122 && copper(second.character) == 0, "A 125 copper stall sale did not round the fee to 3.");
			wallet(second.character, 100);
			int held = copper(second.character);
			int feeNow = fees(seller.character);
			var wrong = market.buyJournaled(second.account, second.generation, second.take(), second.character, buy(second, listings.get(6), 99));
			check("CONFLICT".equals(status(wrong)) && copper(second.character) == held && fees(seller.character) == feeNow && "OPEN".equals(state(listings.get(6))), "A changed price still moved copper.");
			var stolen = market.cancelJournaled(third.account, third.generation, third.take(), third.character, cancel(third, listings.get(3)));
			var returned = market.cancelJournaled(seller.account, seller.generation, seller.take(), seller.character, cancel(seller, listings.get(3)));
			var claimedByBuyer = market.claimJournaled(third.account, third.generation, third.take(), third.character, claim(third, stashes.get(3)));
			int owned = quantity(seller.character, "material.iron_ingot");
			var claimed = market.claimJournaled(seller.account, seller.generation, seller.take(), seller.character, claim(seller, stashes.get(3)));
			check("NOT_OWNER".equals(status(stolen)) && "OK".equals(status(returned)) && "STASH".equals(returned.result().get("location").getAsString()) && "NOT_OWNER".equals(status(claimedByBuyer)) && "OK".equals(status(claimed)) && quantity(seller.character, "material.iron_ingot") == owned + 1, "Cancel or claim crossed accounts.");
			expire(listings.get(4));
			wallet(third.character, 100);
			int thirdCopper = copper(third.character);
			var late = market.buyJournaled(third.account, third.generation, third.take(), third.character, buy(third, listings.get(4), 100));
			check("CONFLICT".equals(status(late)) && "EXPIRED".equals(state(listings.get(4))) && "STASH".equals(location(stashes.get(4))) && copper(third.character) == thirdCopper, "An expired listing still sold.");
			wallet(second.character, 100);
			check(settle(seller, second, listings.get(5), stashes.get(5)), "Buy and cancel both changed one listing.");
			System.out.println("PASS stall cap, seven-day listing, offline 2 percent fee, cancel race, and binding");
		} finally {
			cleanup(sellerId);
			cleanup(firstId);
			cleanup(secondId);
			cleanup(thirdId);
		}
	}

	private static boolean race(Person seller, Person left, Person right, UUID listing) throws Exception {
		var one = buy(left, listing, 100);
		var two = buy(right, listing, 100);
		long leftId = left.take();
		long rightId = right.take();
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			var first = pool.submit(() -> { start.await(); return new MarketRepository().buyJournaled(left.account, left.generation, leftId, left.character, one); });
			var second = pool.submit(() -> { start.await(); return new MarketRepository().buyJournaled(right.account, right.generation, rightId, right.character, two); });
			start.countDown();
			String sa = status(first.get(10, TimeUnit.SECONDS));
			String sb = status(second.get(10, TimeUnit.SECONDS));
			int low = Math.min(copper(left.character), copper(right.character));
			int high = Math.max(copper(left.character), copper(right.character));
			return ((sa.equals("OK") && sb.equals("CONFLICT")) || (sa.equals("CONFLICT") && sb.equals("OK"))) && low == 0 && high == 100;
		} finally { pool.shutdownNow(); }
	}

	private static boolean settle(Person seller, Person buyer, UUID listing, UUID stash) throws Exception {
		int sellerCopper = copper(seller.character);
		int buyerCopper = copper(buyer.character);
		int fee = fees(seller.character);
		int owned = quantity(buyer.character, "material.iron_ingot");
		var purchase = buy(buyer, listing, 100);
		var returned = cancel(seller, listing);
		long buyId = buyer.take();
		long cancelId = seller.take();
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			var bought = pool.submit(() -> { start.await(); return new MarketRepository().buyJournaled(buyer.account, buyer.generation, buyId, buyer.character, purchase); });
			var cancelled = pool.submit(() -> { start.await(); return new MarketRepository().cancelJournaled(seller.account, seller.generation, cancelId, seller.character, returned); });
			start.countDown();
			String sale = status(bought.get(10, TimeUnit.SECONDS));
			String stop = status(cancelled.get(10, TimeUnit.SECONDS));
			if (sale.equals("OK") && stop.equals("CONFLICT")) return copper(seller.character) == sellerCopper + 98 && copper(buyer.character) == buyerCopper - 100 && quantity(buyer.character, "material.iron_ingot") == owned + 1 && fees(seller.character) == fee - 2;
			if (stop.equals("OK") && sale.equals("CONFLICT")) return copper(seller.character) == sellerCopper && copper(buyer.character) == buyerCopper && fees(seller.character) == fee && "STASH".equals(location(stash));
			return false;
		} finally { pool.shutdownNow(); }
	}

	private static String status(MarketRepository.Journaled result) { return result.result().get("status").getAsString(); }

	private static RequestEnvelope stash(Person person, String material, int quantity) {
		var body = new JsonObject();
		body.addProperty("materialId", material);
		body.addProperty("quantity", quantity);
		return new RequestEnvelope(UUID.randomUUID(), person.generation, person.character, "stash_market", body);
	}

	private static RequestEnvelope list(Person person, UUID stash, String channel, int price) {
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

	private static void wallet(UUID character, int copper) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("INSERT INTO dalmaru.character_wallet(character_id, copper) VALUES(?, ?) ON CONFLICT(character_id) DO UPDATE SET copper=EXCLUDED.copper")) {
			update.setObject(1, character);
			update.setInt(2, copper);
			update.executeUpdate();
		}
	}

	private static void stock(UUID character, String material, int quantity, String binding) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, ?) ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=EXCLUDED.quantity, binding=EXCLUDED.binding")) {
			update.setObject(1, character);
			update.setString(2, material);
			update.setInt(3, quantity);
			update.setString(4, binding);
			update.executeUpdate();
		}
	}

	private static void offline(UUID account) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?")) {
			update.setObject(1, account);
			update.executeUpdate();
		}
	}

	private static void expire(UUID listing) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("UPDATE dalmaru.market_listings SET expires_at=clock_timestamp() - interval '1 minute' WHERE listing_id=?")) {
			update.setObject(1, listing);
			if (update.executeUpdate() != 1) throw new SQLException("Stall expiry was not moved.");
		}
	}

	private static int copper(UUID character) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?")) {
			query.setObject(1, character);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private static int fees(UUID character) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT coalesce(sum(copper_delta), 0) FROM dalmaru.character_currency_ledger WHERE character_id=? AND reason='market.fee'")) {
			query.setObject(1, character);
			try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private static int quantity(UUID character, String material) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			query.setObject(1, character);
			query.setString(2, material);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private static int open(UUID character) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT count(*) FROM dalmaru.market_listings WHERE seller_character=? AND state='OPEN'")) {
			query.setObject(1, character);
			try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private static String location(UUID stash) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT location FROM dalmaru.market_stashes WHERE stash_id=?")) {
			query.setObject(1, stash);
			try (var row = query.executeQuery()) { row.next(); return row.getString(1); }
		}
	}

	private static String state(UUID listing) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT state FROM dalmaru.market_listings WHERE listing_id=?")) {
			query.setObject(1, listing);
			try (var row = query.executeQuery()) { row.next(); return row.getString(1); }
		}
	}

	private static double seconds(UUID listing) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT EXTRACT(EPOCH FROM (expires_at - clock_timestamp())) FROM dalmaru.market_listings WHERE listing_id=?")) {
			query.setObject(1, listing);
			try (var row = query.executeQuery()) { row.next(); return row.getDouble(1); }
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
					"DELETE FROM dalmaru.account_home_presence WHERE housing_id IN (SELECT housing_id FROM dalmaru.account_homes WHERE account_id=?)",
					"DELETE FROM dalmaru.account_homes WHERE account_id=?",
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
