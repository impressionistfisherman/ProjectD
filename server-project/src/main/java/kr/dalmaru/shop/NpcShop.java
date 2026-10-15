package kr.dalmaru.shop;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.account.SupplyStay;
import kr.dalmaru.content.VillageCatalog;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** NPC buy and sell. Price, balance, and binding are judged here. Client coordinates are not read. */
public final class NpcShop {
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public Journaled actJournaled(UUID accountId, long generation, long requestId, UUID characterId, String itemId, int quantity, String direction, String dimension, int x, int y, int z, RequestEnvelope envelope) throws SQLException {
		if (generation < 1 || requestId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid shop request.");
		var body = envelope.payload();
		if (!itemId.equals(text(body, "itemId")) || quantity != body.get("quantity").getAsInt() || !direction.equals(text(body, "direction"))) throw new IllegalArgumentException("Shop envelope does not match.");
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, accountId, requestId, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = trade(connection, characterId, itemId, quantity, direction, dimension, x, y, z, stored.receipt().eventId());
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private JsonObject trade(java.sql.Connection connection, UUID characterId, String itemId, int quantity, String direction, String dimension, int x, int y, int z, UUID eventId) throws SQLException {
		if (quantity < 1 || (!"buy".equals(direction) && !"sell".equals(direction))) throw new IllegalArgumentException("Invalid shop trade.");
		if (VillageCatalog.current().stream().noneMatch(village -> village.zone().contains(dimension, x, y, z))) throw new IllegalArgumentException("Not at a shop.");
		boolean buy = "buy".equals(direction);
		int unit = buy ? ShopCatalog.buyPrice(itemId) : ShopCatalog.sellPrice(itemId);
		long cost = (long) unit * quantity;
		if (cost > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid shop trade.");
		Held row = lockMaterial(connection, characterId, itemId);
		int wallet = lockWallet(connection, characterId);
		int owned = row == null ? 0 : row.quantity();
		String binding = row == null ? "FREE" : row.binding();
		if (buy) {
			if (row != null && !"FREE".equals(binding)) throw new IllegalArgumentException("Material binding does not match the shop.");
			if (wallet < cost) throw new IllegalArgumentException("Insufficient copper.");
		} else {
			if (row == null) throw new IllegalArgumentException("Insufficient material.");
			SupplyStay.refuseMove(connection, itemId, binding);
			if (owned < quantity) throw new IllegalArgumentException("Insufficient material.");
		}
		int nextWallet = wallet + (buy ? -(int) cost : (int) cost);
		int nextOwned = owned + (buy ? quantity : -quantity);
		setWallet(connection, characterId, nextWallet);
		setMaterial(connection, characterId, itemId, nextOwned, buy ? "FREE" : binding);
		try (var ledger = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id, character_id, source_run_id, reason, copper_delta) VALUES(?, ?, NULL, ?, ?)")) {
			ledger.setObject(1, eventId);
			ledger.setObject(2, characterId);
			ledger.setString(3, buy ? "shop.buy" : "shop.sell");
			ledger.setInt(4, buy ? -(int) cost : (int) cost);
			ledger.executeUpdate();
		}
		var outcome = new JsonObject();
		outcome.addProperty("itemId", itemId);
		outcome.addProperty("direction", direction);
		outcome.addProperty("quantity", quantity);
		outcome.addProperty("copper", nextWallet);
		outcome.addProperty("owned", nextOwned);
		return outcome;
	}

	private int lockWallet(java.sql.Connection connection, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT copper FROM dalmaru.character_wallet WHERE character_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private void setWallet(java.sql.Connection connection, UUID characterId, int copper) throws SQLException {
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_wallet(character_id, copper) VALUES(?, ?) ON CONFLICT(character_id) DO UPDATE SET copper=EXCLUDED.copper")) {
			update.setObject(1, characterId);
			update.setInt(2, copper);
			update.executeUpdate();
		}
	}

	private Held lockMaterial(java.sql.Connection connection, UUID characterId, String itemId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT quantity, binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, itemId);
			try (var row = query.executeQuery()) { return row.next() ? new Held(row.getInt(1), row.getString(2)) : null; }
		}
	}

	private void setMaterial(java.sql.Connection connection, UUID characterId, String itemId, int quantity, String binding) throws SQLException {
		if (quantity == 0) {
			try (var delete = connection.prepareStatement("DELETE FROM dalmaru.character_materials WHERE character_id=? AND material_id=? AND binding=?")) {
				delete.setObject(1, characterId);
				delete.setString(2, itemId);
				delete.setString(3, binding);
				if (delete.executeUpdate() != 1) throw new IllegalArgumentException("Material binding does not match the shop.");
			}
			return;
		}
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, ?) ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=EXCLUDED.quantity WHERE dalmaru.character_materials.binding=EXCLUDED.binding")) {
			update.setObject(1, characterId);
			update.setString(2, itemId);
			update.setInt(3, quantity);
			update.setString(4, binding);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Material binding does not match the shop.");
		}
	}

	private static String text(JsonObject body, String key) {
		if (!body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid shop body.");
		return body.get(key).getAsString();
	}

	private java.sql.Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	private record Held(int quantity, String binding) { }
}
