package kr.dalmaru.account;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Account copper and materials stay when the active character changes. Character binding and granted supplies cannot enter. */
public final class AccountVault {
	public static final String COPPER = "copper";
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public Journaled actJournaled(UUID accountId, long generation, long requestId, UUID characterId, String assetId, int quantity, String direction, RequestEnvelope envelope) throws SQLException {
		if (generation < 1 || requestId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid vault request.");
		var body = envelope.payload();
		if (!assetId.equals(text(body, "assetId")) || quantity != body.get("quantity").getAsInt() || !direction.equals(text(body, "direction"))) throw new IllegalArgumentException("Vault envelope does not match.");
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, accountId, requestId, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = move(connection, accountId, characterId, assetId, quantity, direction, stored.receipt().eventId());
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private JsonObject move(java.sql.Connection connection, UUID accountId, UUID characterId, String assetId, int quantity, String direction, UUID eventId) throws SQLException {
		if (quantity < 1 || assetId == null || assetId.isBlank() || (!"deposit".equals(direction) && !"withdraw".equals(direction))) throw new IllegalArgumentException("Invalid vault move.");
		boolean deposit = "deposit".equals(direction);
		// ponytail: always lock the character row before the account row. Two-account ordering is unnecessary while a vault serves one account.
		int characterQuantity = COPPER.equals(assetId) ? moveCopper(connection, accountId, characterId, quantity, deposit, eventId) : moveMaterial(connection, accountId, characterId, assetId, quantity, deposit);
		return outcome(assetId, direction, quantity, characterQuantity, holding(connection, accountId, assetId));
	}

	private int moveCopper(java.sql.Connection connection, UUID accountId, UUID characterId, int quantity, boolean deposit, UUID eventId) throws SQLException {
		int wallet = lockWallet(connection, characterId);
		int stored = lockHolding(connection, accountId, COPPER, "FREE");
		if (deposit && wallet < quantity || !deposit && stored < quantity) throw new IllegalArgumentException("Insufficient copper.");
		setWallet(connection, characterId, wallet + (deposit ? -quantity : quantity));
		setHolding(connection, accountId, COPPER, stored + (deposit ? quantity : -quantity), "FREE");
		try (var ledger = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id, character_id, source_run_id, reason, copper_delta) VALUES(?, ?, NULL, ?, ?)")) {
			ledger.setObject(1, eventId);
			ledger.setObject(2, characterId);
			ledger.setString(3, deposit ? "vault.deposit" : "vault.withdraw");
			ledger.setInt(4, deposit ? -quantity : quantity);
			ledger.executeUpdate();
		}
		return wallet + (deposit ? -quantity : quantity);
	}

	private int moveMaterial(java.sql.Connection connection, UUID accountId, UUID characterId, String assetId, int quantity, boolean deposit) throws SQLException {
		Held row = lockMaterial(connection, characterId, assetId);
		if (deposit) {
			String binding = row == null ? "FREE" : row.binding();
			int owned = row == null ? 0 : row.quantity();
			SupplyStay.refuseMove(connection, assetId, binding);
			int stored = lockHolding(connection, accountId, assetId, binding);
			if (owned < quantity) throw new IllegalArgumentException("Insufficient material.");
			setMaterial(connection, characterId, assetId, owned - quantity, binding);
			setHolding(connection, accountId, assetId, stored + quantity, binding);
			return owned - quantity;
		}
		Held stored = lockHolding(connection, accountId, assetId);
		if (stored == null || stored.quantity() < quantity) throw new IllegalArgumentException("Insufficient material.");
		if (row != null && !row.binding().equals(stored.binding())) throw new IllegalArgumentException("Material binding does not match the vault.");
		String binding = row == null ? stored.binding() : row.binding();
		SupplyStay.refuseMove(connection, assetId, binding);
		int owned = row == null ? 0 : row.quantity();
		setMaterial(connection, characterId, assetId, owned + quantity, binding);
		setHolding(connection, accountId, assetId, stored.quantity() - quantity, binding);
		return owned + quantity;
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

	private Held lockMaterial(java.sql.Connection connection, UUID characterId, String assetId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT quantity, binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, assetId);
			try (var row = query.executeQuery()) { return row.next() ? new Held(row.getInt(1), row.getString(2)) : null; }
		}
	}

	private void setMaterial(java.sql.Connection connection, UUID characterId, String assetId, int quantity, String binding) throws SQLException {
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, ?) ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=EXCLUDED.quantity WHERE dalmaru.character_materials.binding=EXCLUDED.binding")) {
			update.setObject(1, characterId);
			update.setString(2, assetId);
			update.setInt(3, quantity);
			update.setString(4, binding);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Material binding does not match the vault.");
		}
	}

	private Held lockHolding(java.sql.Connection connection, UUID accountId, String assetId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT quantity, binding FROM dalmaru.account_holdings WHERE account_id=? AND asset_id=? FOR UPDATE")) {
			query.setObject(1, accountId);
			query.setString(2, assetId);
			try (var row = query.executeQuery()) { return row.next() ? new Held(row.getInt(1), row.getString(2)) : null; }
		}
	}

	private int lockHolding(java.sql.Connection connection, UUID accountId, String assetId, String binding) throws SQLException {
		Held stored = lockHolding(connection, accountId, assetId);
		if (stored == null) return 0;
		if (!binding.equals(stored.binding())) throw new IllegalArgumentException("Material binding does not match the vault.");
		return stored.quantity();
	}

	private void setHolding(java.sql.Connection connection, UUID accountId, String assetId, int quantity, String binding) throws SQLException {
		if (quantity == 0) {
			try (var delete = connection.prepareStatement("DELETE FROM dalmaru.account_holdings WHERE account_id=? AND asset_id=?")) {
				delete.setObject(1, accountId);
				delete.setString(2, assetId);
				delete.executeUpdate();
			}
			return;
		}
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.account_holdings(account_id, asset_id, quantity, binding) VALUES(?, ?, ?, ?) ON CONFLICT(account_id, asset_id) DO UPDATE SET quantity=EXCLUDED.quantity WHERE dalmaru.account_holdings.binding=EXCLUDED.binding")) {
			update.setObject(1, accountId);
			update.setString(2, assetId);
			update.setInt(3, quantity);
			update.setString(4, binding);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Material binding does not match the vault.");
		}
	}

	private int holding(java.sql.Connection connection, UUID accountId, String assetId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT quantity FROM dalmaru.account_holdings WHERE account_id=? AND asset_id=?")) {
			query.setObject(1, accountId);
			query.setString(2, assetId);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private static JsonObject outcome(String assetId, String direction, int quantity, int characterQuantity, int accountQuantity) {
		var outcome = new JsonObject();
		outcome.addProperty("assetId", assetId);
		outcome.addProperty("direction", direction);
		outcome.addProperty("quantity", quantity);
		outcome.addProperty("characterQuantity", characterQuantity);
		outcome.addProperty("accountQuantity", accountQuantity);
		return outcome;
	}

	private static String text(JsonObject body, String key) {
		if (!body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid vault body.");
		return body.get(key).getAsString();
	}

	private java.sql.Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	private record Held(int quantity, String binding) { }
}
