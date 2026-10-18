package kr.dalmaru.trade;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;
import kr.dalmaru.account.SupplyStay;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Both accounts must confirm before escrowed materials move. Cancel and expiry give them back. */
public final class DirectTrade {
	private static final int EXPIRY_SECONDS = loadExpiry();
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public Journaled actJournaled(UUID accountId, long generation, long requestId, UUID characterId, String action, UUID offerId, UUID partnerAccountId, String itemId, int quantity, int version, RequestEnvelope envelope) throws SQLException {
		if (generation < 1 || requestId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid trade request.");
		if ("open".equals(action) && (partnerAccountId == null || partnerAccountId.equals(accountId))) throw new IllegalArgumentException("Invalid trade offer.");
		if (!"open".equals(action) && offerId == null) throw new IllegalArgumentException("Invalid trade offer.");
		var body = envelope.payload();
		if (!action.equals(text(body, "action"))) throw new IllegalArgumentException("Trade envelope does not match.");
		if (!"open".equals(action) && !offerId.toString().equals(text(body, "offerId"))) throw new IllegalArgumentException("Trade envelope does not match.");
		if (("open".equals(action) || "deposit".equals(action)) && (!itemId.equals(text(body, "itemId")) || !body.has("quantity") || !body.get("quantity").isJsonPrimitive() || body.get("quantity").getAsInt() != quantity)) throw new IllegalArgumentException("Trade envelope does not match.");
		if ("confirm".equals(action) && (!body.has("version") || !body.get("version").isJsonPrimitive() || body.get("version").getAsInt() != version)) throw new IllegalArgumentException("Trade envelope does not match.");
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				UUID other = "open".equals(action) ? partnerAccountId : partnerOf(connection, offerId, accountId);
				lockAccounts(connection, accountId, other);
				var stored = RequestJournal.begin(connection, accountId, requestId, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = switch (action) {
					case "open" -> open(connection, accountId, characterId, partnerAccountId, itemId, quantity);
					case "deposit" -> deposit(connection, accountId, characterId, offerId, itemId, quantity);
					case "withdraw" -> withdraw(connection, accountId, offerId);
					case "confirm" -> confirm(connection, accountId, offerId, version);
					case "cancel" -> cancel(connection, accountId, offerId);
					default -> throw new IllegalArgumentException("Invalid trade action.");
				};
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private JsonObject open(Connection connection, UUID accountId, UUID characterId, UUID partnerAccountId, String itemId, int quantity) throws SQLException {
		if (quantity < 1) throw new IllegalArgumentException("Invalid trade offer.");
		UUID offerId = UUID.randomUUID();
		String binding = "copper".equals(itemId) ? "FREE" : takeBinding(connection, characterId, itemId);
		escrow(connection, characterId, itemId, quantity);
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.trade_offers(offer_id, opener_account_id, partner_account_id, state, expires_at, version) VALUES(?, ?, ?, 'reserved', clock_timestamp() + make_interval(secs => ?), 1)")) {
			insert.setObject(1, offerId);
			insert.setObject(2, accountId);
			insert.setObject(3, partnerAccountId);
			insert.setInt(4, EXPIRY_SECONDS);
			insert.executeUpdate();
		}
		insertLine(connection, offerId, accountId, characterId, itemId, quantity, binding);
		return outcome(offerId, "reserved", 1);
	}

	private JsonObject deposit(Connection connection, UUID accountId, UUID characterId, UUID offerId, String itemId, int quantity) throws SQLException {
		var offer = lockOffer(connection, offerId);
		if (!accountId.equals(offer.partner)) throw new IllegalArgumentException("Only the partner can deposit.");
		if (expired(connection, offerId)) return expire(connection, offerId);
		if (!"reserved".equals(offer.state) || line(connection, offerId, accountId) != null || quantity < 1) throw new IllegalArgumentException("Trade deposit is closed.");
		String binding = "copper".equals(itemId) ? "FREE" : takeBinding(connection, characterId, itemId);
		escrow(connection, characterId, itemId, quantity);
		insertLine(connection, offerId, accountId, characterId, itemId, quantity, binding);
		return outcome(offerId, "reserved", bump(connection, offerId));
	}

	private JsonObject withdraw(Connection connection, UUID accountId, UUID offerId) throws SQLException {
		var offer = lockOffer(connection, offerId);
		if (!accountId.equals(offer.opener) && !accountId.equals(offer.partner)) throw new IllegalArgumentException("Trade withdraw is closed.");
		if (expired(connection, offerId)) return expire(connection, offerId);
		if (!"reserved".equals(offer.state)) throw new IllegalArgumentException("Trade withdraw is closed.");
		Line row = line(connection, offerId, accountId);
		if (row == null) throw new IllegalArgumentException("Trade withdraw is closed.");
		give(connection, row.characterId, row.itemId, row.quantity, row.binding);
		try (var delete = connection.prepareStatement("DELETE FROM dalmaru.trade_lines WHERE offer_id=? AND account_id=?")) {
			delete.setObject(1, offerId);
			delete.setObject(2, accountId);
			delete.executeUpdate();
		}
		return outcome(offerId, "reserved", bump(connection, offerId));
	}

	private JsonObject confirm(Connection connection, UUID accountId, UUID offerId, int version) throws SQLException {
		var offer = lockOffer(connection, offerId);
		if (!accountId.equals(offer.opener) && !accountId.equals(offer.partner)) throw new IllegalArgumentException("Trade confirm is closed.");
		if (expired(connection, offerId)) return expire(connection, offerId);
		if (!"reserved".equals(offer.state) || version != offer.version) throw new IllegalArgumentException("Trade confirm is closed.");
		if (line(connection, offerId, offer.opener) == null || line(connection, offerId, offer.partner) == null) throw new IllegalArgumentException("Trade lines must be deposited before confirm.");
		try (var update = connection.prepareStatement(accountId.equals(offer.opener)
			? "UPDATE dalmaru.trade_offers SET opener_confirmed=TRUE WHERE offer_id=?"
			: "UPDATE dalmaru.trade_offers SET partner_confirmed=TRUE WHERE offer_id=?")) {
			update.setObject(1, offerId);
			update.executeUpdate();
		}
		if (!bothConfirmed(connection, offerId)) return outcome(offerId, "reserved", offer.version);
		var openerLine = line(connection, offerId, offer.opener);
		var partnerLine = line(connection, offerId, offer.partner);
		give(connection, partnerLine.characterId, openerLine.itemId, openerLine.quantity, openerLine.binding);
		give(connection, openerLine.characterId, partnerLine.itemId, partnerLine.quantity, partnerLine.binding);
		setState(connection, offerId, "completed");
		return outcome(offerId, "completed", offer.version);
	}

	private JsonObject cancel(Connection connection, UUID accountId, UUID offerId) throws SQLException {
		var offer = lockOffer(connection, offerId);
		if (!accountId.equals(offer.opener) && !accountId.equals(offer.partner)) throw new IllegalArgumentException("Trade cancel is closed.");
		if (expired(connection, offerId)) return expire(connection, offerId);
		if (!"reserved".equals(offer.state)) throw new IllegalArgumentException("Trade cancel is closed.");
		returnLines(connection, offerId);
		setState(connection, offerId, "cancelled");
		return outcome(offerId, "cancelled", offer.version);
	}

	private JsonObject expire(Connection connection, UUID offerId) throws SQLException {
		var offer = lockOffer(connection, offerId);
		returnLines(connection, offerId);
		setState(connection, offerId, "expired");
		return outcome(offerId, "expired", offer.version);
	}

	private void escrow(Connection connection, UUID characterId, String itemId, int quantity) throws SQLException {
		if ("copper".equals(itemId)) { moveCopper(connection, characterId, -quantity, "trade.out"); return; }
		Held row = lockMaterial(connection, characterId, itemId);
		if (row == null || row.quantity < quantity) throw new IllegalArgumentException("Insufficient material.");
		SupplyStay.refuseMove(connection, itemId, row.binding);
		setMaterial(connection, characterId, itemId, row.quantity - quantity, row.binding);
	}

	private String takeBinding(Connection connection, UUID characterId, String itemId) throws SQLException {
		Held row = lockMaterial(connection, characterId, itemId);
		if (row == null) throw new IllegalArgumentException("Insufficient material.");
		return row.binding;
	}

	private void give(Connection connection, UUID characterId, String itemId, int quantity, String binding) throws SQLException {
		if ("copper".equals(itemId)) { moveCopper(connection, characterId, quantity, "trade.in"); return; }
		Held row = lockMaterial(connection, characterId, itemId);
		if (row != null && !row.binding.equals(binding)) throw new IllegalArgumentException("Material binding does not match the trade.");
		setMaterial(connection, characterId, itemId, (row == null ? 0 : row.quantity) + quantity, binding);
	}

	private void returnLines(Connection connection, UUID offerId) throws SQLException {
		for (var row : lines(connection, offerId)) give(connection, row.characterId, row.itemId, row.quantity, row.binding);
	}

	private void moveCopper(Connection connection, UUID characterId, int delta, String reason) throws SQLException {
		int wallet = 0;
		try (var query = connection.prepareStatement("SELECT copper FROM dalmaru.character_wallet WHERE character_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			try (var row = query.executeQuery()) { if (row.next()) wallet = row.getInt(1); }
		}
		if (wallet + delta < 0) throw new IllegalArgumentException("Insufficient copper.");
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=? WHERE character_id=?")) {
			update.setInt(1, wallet + delta);
			update.setObject(2, characterId);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Insufficient copper.");
		}
		try (var ledger = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id, character_id, source_run_id, reason, copper_delta) VALUES(?, ?, NULL, ?, ?)")) {
			ledger.setObject(1, UUID.randomUUID());
			ledger.setObject(2, characterId);
			ledger.setString(3, reason);
			ledger.setInt(4, delta);
			ledger.executeUpdate();
		}
	}

	private int bump(Connection connection, UUID offerId) throws SQLException {
		try (var update = connection.prepareStatement("UPDATE dalmaru.trade_offers SET version=version+1, opener_confirmed=FALSE, partner_confirmed=FALSE WHERE offer_id=? AND state='reserved' RETURNING version")) {
			update.setObject(1, offerId);
			try (var row = update.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Trade state changed.");
				return row.getInt(1);
			}
		}
	}

	private void insertLine(Connection connection, UUID offerId, UUID accountId, UUID characterId, String itemId, int quantity, String binding) throws SQLException {
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.trade_lines(offer_id, account_id, character_id, item_id, quantity, binding) VALUES(?, ?, ?, ?, ?, ?)")) {
			insert.setObject(1, offerId);
			insert.setObject(2, accountId);
			insert.setObject(3, characterId);
			insert.setString(4, itemId);
			insert.setInt(5, quantity);
			insert.setString(6, binding);
			insert.executeUpdate();
		}
	}

	private void lockAccounts(Connection connection, UUID left, UUID right) throws SQLException {
		UUID first = left.compareTo(right) <= 0 ? left : right;
		UUID second = first.equals(left) ? right : left;
		lockAccount(connection, first);
		if (!second.equals(first)) lockAccount(connection, second);
	}

	private void lockAccount(Connection connection, UUID accountId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT account_id FROM dalmaru.accounts WHERE account_id=? FOR UPDATE")) {
			query.setObject(1, accountId);
			try (var row = query.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Unknown trade account."); }
		}
	}

	private Offer lockOffer(Connection connection, UUID offerId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT opener_account_id, partner_account_id, state, version FROM dalmaru.trade_offers WHERE offer_id=? FOR UPDATE")) {
			query.setObject(1, offerId);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Unknown trade offer.");
				return new Offer(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getString(3), row.getInt(4));
			}
		}
	}

	private UUID partnerOf(Connection connection, UUID offerId, UUID accountId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT opener_account_id, partner_account_id FROM dalmaru.trade_offers WHERE offer_id=?")) {
			query.setObject(1, offerId);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Unknown trade offer.");
				UUID opener = row.getObject(1, UUID.class);
				UUID partner = row.getObject(2, UUID.class);
				if (!accountId.equals(opener) && !accountId.equals(partner)) throw new IllegalArgumentException("Trade request is closed.");
				return accountId.equals(opener) ? partner : opener;
			}
		}
	}

	private boolean expired(Connection connection, UUID offerId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT state='reserved' AND expires_at <= clock_timestamp() FROM dalmaru.trade_offers WHERE offer_id=?")) {
			query.setObject(1, offerId);
			try (var row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
		}
	}

	private boolean bothConfirmed(Connection connection, UUID offerId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT opener_confirmed AND partner_confirmed FROM dalmaru.trade_offers WHERE offer_id=?")) {
			query.setObject(1, offerId);
			try (var row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
		}
	}

	private void setState(Connection connection, UUID offerId, String state) throws SQLException {
		try (var update = connection.prepareStatement("UPDATE dalmaru.trade_offers SET state=? WHERE offer_id=? AND state='reserved'")) {
			update.setString(1, state);
			update.setObject(2, offerId);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Trade state changed.");
		}
	}

	private Line line(Connection connection, UUID offerId, UUID accountId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT character_id, item_id, quantity, binding FROM dalmaru.trade_lines WHERE offer_id=? AND account_id=?")) {
			query.setObject(1, offerId);
			query.setObject(2, accountId);
			try (var row = query.executeQuery()) { return row.next() ? new Line(row.getObject(1, UUID.class), row.getString(2), row.getInt(3), row.getString(4)) : null; }
		}
	}

	private java.util.List<LineAccount> lines(Connection connection, UUID offerId) throws SQLException {
		var rows = new ArrayList<LineAccount>();
		try (var query = connection.prepareStatement("SELECT account_id, character_id, item_id, quantity, binding FROM dalmaru.trade_lines WHERE offer_id=? ORDER BY account_id")) {
			query.setObject(1, offerId);
			try (var row = query.executeQuery()) { while (row.next()) rows.add(new LineAccount(row.getObject(2, UUID.class), row.getString(3), row.getInt(4), row.getString(5))); }
		}
		return rows;
	}

	private Held lockMaterial(Connection connection, UUID characterId, String itemId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT quantity, binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, itemId);
			try (var row = query.executeQuery()) { return row.next() ? new Held(row.getInt(1), row.getString(2)) : null; }
		}
	}

	private void setMaterial(Connection connection, UUID characterId, String itemId, int quantity, String binding) throws SQLException {
		if (quantity == 0) {
			try (var delete = connection.prepareStatement("DELETE FROM dalmaru.character_materials WHERE character_id=? AND material_id=? AND binding=?")) {
				delete.setObject(1, characterId);
				delete.setString(2, itemId);
				delete.setString(3, binding);
				if (delete.executeUpdate() != 1) throw new IllegalArgumentException("Material binding does not match the trade.");
			}
			return;
		}
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, ?) ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=EXCLUDED.quantity WHERE dalmaru.character_materials.binding=EXCLUDED.binding")) {
			update.setObject(1, characterId);
			update.setString(2, itemId);
			update.setInt(3, quantity);
			update.setString(4, binding);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Material binding does not match the trade.");
		}
	}

	private static JsonObject outcome(UUID offerId, String state, int version) {
		var outcome = new JsonObject();
		outcome.addProperty("offerId", offerId.toString());
		outcome.addProperty("state", state);
		outcome.addProperty("version", version);
		return outcome;
	}

	private static String text(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid trade body.");
		return body.get(key).getAsString();
	}

	private Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	private static int loadExpiry() {
		try (var stream = DirectTrade.class.getResourceAsStream("/content/trade.json")) {
			if (stream == null) throw new IllegalStateException("Missing trade rules");
			int seconds = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().get("expirySeconds").getAsInt();
			if (seconds < 1) throw new IllegalStateException("Invalid trade expiry");
			return seconds;
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot load trade rules", exception);
		}
	}

	private record Offer(UUID opener, UUID partner, String state, int version) { }
	private record Held(int quantity, String binding) { }
	private record Line(UUID characterId, String itemId, int quantity, String binding) { }
	private record LineAccount(UUID characterId, String itemId, int quantity, String binding) { }
}
