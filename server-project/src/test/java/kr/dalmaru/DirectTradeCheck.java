package kr.dalmaru;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.trade.DirectTrade;

public final class DirectTradeCheck {
	private static final UUID OPENER = UUID.fromString("00000000-0000-4000-8000-00000000a536");
	private static final UUID PARTNER = UUID.fromString("00000000-0000-4000-8000-00000000a537");
	private DirectTradeCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		applyMigration();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var trade = new DirectTrade();
		try {
			long openerGeneration = accounts.openSession(OPENER).connectionGeneration();
			long partnerGeneration = accounts.openSession(PARTNER).connectionGeneration();
			UUID opener = characters.create(OPENER, 1, "달마루_거래").characterId();
			UUID partner = characters.create(PARTNER, 1, "달마루_상대").characterId();
			characters.selectActive(OPENER, opener);
			characters.selectActive(PARTNER, partner);
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.ironstone', 1, 'CHARACTER')", opener);
			reject(() -> act(trade, OPENER, openerGeneration, 1, opener, "open", null, PARTNER, "material.ironstone", 1, 0));
			require(material(opener, "material.ironstone") == 1, "Character-bound material was offered.");
			sql("UPDATE dalmaru.character_materials SET quantity=3, binding='FREE' WHERE character_id=? AND material_id='material.ironstone'", opener);
			reject(() -> act(trade, OPENER, openerGeneration, 2, opener, "open", null, PARTNER, "supply.recovery", 1, 0));
			var opened = act(trade, OPENER, openerGeneration, 3, opener, "open", null, PARTNER, "material.ironstone", 2, 0);
			var replay = act(trade, OPENER, openerGeneration, 3, opener, "open", null, PARTNER, "material.ironstone", 2, 0);
			UUID offer = UUID.fromString(opened.result().get("offerId").getAsString());
			require(!opened.replayed() && replay.replayed() && material(opener, "material.ironstone") == 1 && "reserved".equals(state(offer)), "Offer did not reserve the stack once.");
			reject(() -> act(trade, OPENER, openerGeneration, 12, opener, "confirm", offer, null, "", 0, 1));
			require("reserved".equals(state(offer)) && !flag(offer, "opener_confirmed"), "Confirm stuck before the partner deposited.");
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'FREE')", partner);
			var deposited = act(trade, PARTNER, partnerGeneration, 4, partner, "deposit", offer, null, "material.iron_ingot", 1, 0);
			require(deposited.result().get("version").getAsInt() == 2 && !flag(offer, "opener_confirmed"), "Deposit did not clear the previous confirm.");
			reject(() -> act(trade, OPENER, openerGeneration, 13, opener, "confirm", offer, null, "", 0, 1));
			var waiting = act(trade, OPENER, openerGeneration, 5, opener, "confirm", offer, null, "", 0, 2);
			require("reserved".equals(waiting.result().get("state").getAsString()) && material(opener, "material.iron_ingot") == 0, "One confirm completed the trade.");
			var done = act(trade, PARTNER, partnerGeneration, 6, partner, "confirm", offer, null, "", 0, 2);
			require("completed".equals(done.result().get("state").getAsString()) && material(opener, "material.iron_ingot") == 1 && material(partner, "material.ironstone") == 2 && lines(offer) == 2, "Both confirms did not swap the escrow.");
			var second = act(trade, OPENER, openerGeneration, 7, opener, "open", null, PARTNER, "material.ironstone", 1, 0);
			UUID cancelOffer = UUID.fromString(second.result().get("offerId").getAsString());
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'FREE')", partner);
			act(trade, PARTNER, partnerGeneration, 8, partner, "deposit", cancelOffer, null, "material.iron_ingot", 1, 0);
			var cancelled = act(trade, OPENER, openerGeneration, 9, opener, "cancel", cancelOffer, null, "", 0, 0);
			require("cancelled".equals(cancelled.result().get("state").getAsString()) && material(opener, "material.ironstone") == 1 && material(partner, "material.iron_ingot") == 1 && lines(cancelOffer) == 2, "Cancel did not return both stacks.");
			var third = act(trade, OPENER, openerGeneration, 10, opener, "open", null, PARTNER, "material.ironstone", 1, 0);
			UUID expiredOffer = UUID.fromString(third.result().get("offerId").getAsString());
			sql("UPDATE dalmaru.trade_offers SET expires_at=clock_timestamp() - interval '1 second' WHERE offer_id=?", expiredOffer);
			var expired = act(trade, PARTNER, partnerGeneration, 11, partner, "confirm", expiredOffer, null, "", 0, 1);
			require("expired".equals(expired.result().get("state").getAsString()) && material(opener, "material.ironstone") == 1 && lines(expiredOffer) == 1, "Expiry kept the escrow.");
			var bait = act(trade, OPENER, openerGeneration, 14, opener, "open", null, PARTNER, "material.ironstone", 1, 0);
			UUID baitOffer = UUID.fromString(bait.result().get("offerId").getAsString());
			act(trade, PARTNER, partnerGeneration, 15, partner, "deposit", baitOffer, null, "material.iron_ingot", 1, 0);
			act(trade, OPENER, openerGeneration, 16, opener, "confirm", baitOffer, null, "", 0, 2);
			var pulled = act(trade, PARTNER, partnerGeneration, 17, partner, "withdraw", baitOffer, null, "", 0, 0);
			require(pulled.result().get("version").getAsInt() == 3 && material(partner, "material.iron_ingot") == 1 && !flag(baitOffer, "opener_confirmed"), "Withdraw kept the old confirm.");
			act(trade, PARTNER, partnerGeneration, 18, partner, "deposit", baitOffer, null, "material.iron_ingot", 1, 0);
			var late = act(trade, PARTNER, partnerGeneration, 19, partner, "confirm", baitOffer, null, "", 0, 4);
			require("reserved".equals(late.result().get("state").getAsString()), "A changed offer completed on the old confirm.");
			reject(() -> act(trade, OPENER, openerGeneration, 20, opener, "confirm", baitOffer, null, "", 0, 2));
			var swapped = act(trade, OPENER, openerGeneration, 21, opener, "confirm", baitOffer, null, "", 0, 4);
			require("completed".equals(swapped.result().get("state").getAsString()) && lines(baitOffer) == 2, "The new version did not complete.");
			int openerCopper = copper(opener);
			int partnerCopper = copper(partner);
			var copperOffer = act(trade, OPENER, openerGeneration, 22, opener, "open", null, PARTNER, "copper", 5, 0);
			UUID copperId = UUID.fromString(copperOffer.result().get("offerId").getAsString());
			act(trade, PARTNER, partnerGeneration, 23, partner, "deposit", copperId, null, "copper", 3, 0);
			act(trade, OPENER, openerGeneration, 24, opener, "confirm", copperId, null, "", 0, 2);
			var paid = act(trade, PARTNER, partnerGeneration, 25, partner, "confirm", copperId, null, "", 0, 2);
			require("completed".equals(paid.result().get("state").getAsString()) && copper(opener) == openerCopper - 5 + 3 && copper(partner) == partnerCopper - 3 + 5 && lines(copperId) == 2 && ledger(opener, "trade.out") == -5 && ledger(opener, "trade.in") == 3 && ledger(partner, "trade.out") == -3 && ledger(partner, "trade.in") == 5, "Copper trade ledger did not move.");
			System.out.println("Direct trade swaps only after both confirms and returns escrow on cancel or expiry PASS");
		} finally {
			cleanup();
		}
	}

	private static DirectTrade.Journaled act(DirectTrade trade, UUID account, long generation, long request, UUID character, String action, UUID offer, UUID partner, String item, int quantity, int version) throws Exception {
		var body = new JsonObject();
		body.addProperty("action", action);
		if (offer != null) body.addProperty("offerId", offer.toString());
		if ("open".equals(action) || "deposit".equals(action)) {
			body.addProperty("itemId", item);
			body.addProperty("quantity", quantity);
		}
		if ("confirm".equals(action)) body.addProperty("version", version);
		var envelope = RequestEnvelope.legacy(account, generation, character, "direct_trade", request, body);
		return trade.actJournaled(account, generation, request, character, action, offer, partner, item, quantity, version, envelope);
	}

	private static void applyMigration() throws Exception {
		apply(33, "V033__trade_offers.sql");
		apply(37, "V037__trade_offer_version.sql");
	}

	private static void apply(int version, String file) throws Exception {
		try (var connection = connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=?")) {
			check.setInt(1, version);
			try (var result = check.executeQuery()) { if (result.next()) return; }
			try (var statement = connection.createStatement()) { statement.execute(Files.readString(Path.of("database", "migrations", file))); }
		}
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Trade accepted a blocked request.");
	}

	private static void sql(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) { statement.setObject(1, id); statement.executeUpdate(); }
	}

	private static int material(UUID id, String item) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			statement.setObject(1, id);
			statement.setString(2, item);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
		}
	}

	private static boolean flag(UUID offer, String column) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT " + column + " FROM dalmaru.trade_offers WHERE offer_id=?")) {
			statement.setObject(1, offer);
			try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
		}
	}

	private static int lines(UUID offer) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.trade_lines WHERE offer_id=?")) {
			statement.setObject(1, offer);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static int copper(UUID character) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?")) {
			statement.setObject(1, character);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
		}
	}

	private static int ledger(UUID character, String reason) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT COALESCE(SUM(copper_delta), 0) FROM dalmaru.character_currency_ledger WHERE character_id=? AND reason=?")) {
			statement.setObject(1, character);
			statement.setString(2, reason);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static String state(UUID offer) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT state FROM dalmaru.trade_offers WHERE offer_id=?")) {
			statement.setObject(1, offer);
			try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
		}
	}

	private static java.sql.Connection connection() throws Exception {
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup() throws Exception {
		for (UUID account : new UUID[] {OPENER, PARTNER}) {
			try (var connection = connection()) {
				for (String query : new String[] {
					"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
					"DELETE FROM dalmaru.trade_lines WHERE account_id=?",
					"DELETE FROM dalmaru.trade_offers WHERE opener_account_id=? OR partner_account_id=?",
					"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_currency_ledger WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.characters WHERE account_id=?",
					"DELETE FROM dalmaru.accounts WHERE account_id=?"
				}) try (var statement = connection.prepareStatement(query)) {
					statement.setObject(1, account);
					if (query.contains("OR partner")) statement.setObject(2, account);
					statement.executeUpdate();
				}
			}
		}
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
