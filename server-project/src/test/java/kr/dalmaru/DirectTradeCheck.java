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
			reject(() -> act(trade, OPENER, openerGeneration, 1, opener, "open", null, PARTNER, "material.ironstone", 1));
			require(material(opener, "material.ironstone") == 1, "Character-bound material was offered.");
			sql("UPDATE dalmaru.character_materials SET quantity=3, binding='FREE' WHERE character_id=? AND material_id='material.ironstone'", opener);
			reject(() -> act(trade, OPENER, openerGeneration, 2, opener, "open", null, PARTNER, "supply.recovery", 1));
			var opened = act(trade, OPENER, openerGeneration, 3, opener, "open", null, PARTNER, "material.ironstone", 2);
			var replay = act(trade, OPENER, openerGeneration, 3, opener, "open", null, PARTNER, "material.ironstone", 2);
			UUID offer = UUID.fromString(opened.result().get("offerId").getAsString());
			require(!opened.replayed() && replay.replayed() && material(opener, "material.ironstone") == 1 && "reserved".equals(state(offer)), "Offer did not reserve the stack once.");
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'FREE')", partner);
			act(trade, PARTNER, partnerGeneration, 4, partner, "deposit", offer, null, "material.iron_ingot", 1);
			var waiting = act(trade, OPENER, openerGeneration, 5, opener, "confirm", offer, null, "", 0);
			require("reserved".equals(waiting.result().get("state").getAsString()) && material(opener, "material.iron_ingot") == 0, "One confirm completed the trade.");
			var done = act(trade, PARTNER, partnerGeneration, 6, partner, "confirm", offer, null, "", 0);
			require("completed".equals(done.result().get("state").getAsString()) && material(opener, "material.iron_ingot") == 1 && material(partner, "material.ironstone") == 2, "Both confirms did not swap the escrow.");
			var second = act(trade, OPENER, openerGeneration, 7, opener, "open", null, PARTNER, "material.ironstone", 1);
			UUID cancelOffer = UUID.fromString(second.result().get("offerId").getAsString());
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.iron_ingot', 1, 'FREE')", partner);
			act(trade, PARTNER, partnerGeneration, 8, partner, "deposit", cancelOffer, null, "material.iron_ingot", 1);
			var cancelled = act(trade, OPENER, openerGeneration, 9, opener, "cancel", cancelOffer, null, "", 0);
			require("cancelled".equals(cancelled.result().get("state").getAsString()) && material(opener, "material.ironstone") == 1 && material(partner, "material.iron_ingot") == 1, "Cancel did not return both stacks.");
			var third = act(trade, OPENER, openerGeneration, 10, opener, "open", null, PARTNER, "material.ironstone", 1);
			UUID expiredOffer = UUID.fromString(third.result().get("offerId").getAsString());
			sql("UPDATE dalmaru.trade_offers SET expires_at=clock_timestamp() - interval '1 second' WHERE offer_id=?", expiredOffer);
			var expired = act(trade, PARTNER, partnerGeneration, 11, partner, "confirm", expiredOffer, null, "", 0);
			require("expired".equals(expired.result().get("state").getAsString()) && material(opener, "material.ironstone") == 1, "Expiry kept the escrow.");
			System.out.println("Direct trade swaps only after both confirms and returns escrow on cancel or expiry PASS");
		} finally {
			cleanup();
		}
	}

	private static DirectTrade.Journaled act(DirectTrade trade, UUID account, long generation, long request, UUID character, String action, UUID offer, UUID partner, String item, int quantity) throws Exception {
		var body = new JsonObject();
		body.addProperty("action", action);
		if (offer != null) body.addProperty("offerId", offer.toString());
		if ("open".equals(action) || "deposit".equals(action)) {
			body.addProperty("itemId", item);
			body.addProperty("quantity", quantity);
		}
		var envelope = RequestEnvelope.legacy(account, generation, character, "direct_trade", request, body);
		return trade.actJournaled(account, generation, request, character, action, offer, partner, item, quantity, envelope);
	}

	private static void applyMigration() throws Exception {
		try (var connection = connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=33")) {
			try (var result = check.executeQuery()) { if (result.next()) return; }
			try (var statement = connection.createStatement()) { statement.execute(Files.readString(Path.of("database", "migrations", "V033__trade_offers.sql"))); }
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
