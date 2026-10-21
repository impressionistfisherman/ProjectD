package kr.dalmaru.gather;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Account gather cooldown and character life experience. The server position is the only location. */
public final class Gathering {
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public Journaled actJournaled(UUID accountId, long generation, long requestId, UUID characterId, String nodeId, String dimension, int x, int y, int z, RequestEnvelope envelope) throws SQLException {
		if (generation < 1 || requestId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid gather request.");
		var body = envelope.payload();
		if (!nodeId.equals(text(body, "nodeId"))) throw new IllegalArgumentException("Gather envelope does not match.");
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, accountId, requestId, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = gather(connection, accountId, characterId, nodeId, dimension, x, y, z);
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private JsonObject gather(java.sql.Connection connection, UUID accountId, UUID characterId, String nodeId, String dimension, int x, int y, int z) throws SQLException {
		var node = GatherCatalog.node(nodeId);
		if (!node.zone().contains(dimension, x, y, z)) throw new IllegalArgumentException("Not at a gather node.");
		int remaining = cooldownRemaining(connection, accountId, nodeId);
		if (remaining > 0) throw new IllegalArgumentException("Gather node is not ready. " + remaining + " seconds remain.");
		Held row = lockMaterial(connection, characterId, node.itemId());
		if (row != null && !row.binding().equals(node.binding())) throw new IllegalArgumentException("Material binding does not match the node.");
		int owned = (row == null ? 0 : row.quantity()) + node.quantity();
		setMaterial(connection, characterId, node.itemId(), owned, node.binding());
		int xp = addLifeXp(connection, characterId, node.lifeXp());
		kr.dalmaru.life.LifeProfessionRepository.grantAction(connection, characterId, kr.dalmaru.life.LifeProfessionRepository.jobForMaterial(node.itemId()));
		startCooldown(connection, accountId, nodeId, node.cooldownSeconds());
		var outcome = new JsonObject();
		outcome.addProperty("nodeId", nodeId);
		outcome.addProperty("itemId", node.itemId());
		outcome.addProperty("owned", owned);
		outcome.addProperty("lifeXp", xp);
		return outcome;
	}

	private int cooldownRemaining(java.sql.Connection connection, UUID accountId, String nodeId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT GREATEST(0, CEIL(EXTRACT(EPOCH FROM (ready_at - clock_timestamp()))))::int FROM dalmaru.account_gather_cooldowns WHERE account_id=? AND node_id=? FOR UPDATE")) {
			query.setObject(1, accountId);
			query.setString(2, nodeId);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private void startCooldown(java.sql.Connection connection, UUID accountId, String nodeId, int seconds) throws SQLException {
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.account_gather_cooldowns(account_id, node_id, ready_at) VALUES(?, ?, clock_timestamp() + make_interval(secs => ?)) ON CONFLICT(account_id, node_id) DO UPDATE SET ready_at=EXCLUDED.ready_at")) {
			update.setObject(1, accountId);
			update.setString(2, nodeId);
			update.setInt(3, seconds);
			update.executeUpdate();
		}
	}

	private int addLifeXp(java.sql.Connection connection, UUID characterId, int amount) throws SQLException {
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_life_xp(character_id, xp) VALUES(?, ?) ON CONFLICT(character_id) DO UPDATE SET xp=dalmaru.character_life_xp.xp + EXCLUDED.xp RETURNING xp")) {
			update.setObject(1, characterId);
			update.setInt(2, amount);
			try (var row = update.executeQuery()) { row.next(); return row.getInt(1); }
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
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, ?) ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=EXCLUDED.quantity WHERE dalmaru.character_materials.binding=EXCLUDED.binding")) {
			update.setObject(1, characterId);
			update.setString(2, itemId);
			update.setInt(3, quantity);
			update.setString(4, binding);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Material binding does not match the node.");
		}
	}

	private static String text(JsonObject body, String key) {
		if (!body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid gather body.");
		return body.get(key).getAsString();
	}

	private java.sql.Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	private record Held(int quantity, String binding) { }
}
