package kr.dalmaru.craft;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.account.SupplyStay;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Precision steps. A failed step returns its material and leaves the original equipment row. */
public final class PrecisionOrderRepository {
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }
	private record Gear(String itemId, int enhancement, String binding) { }

	public Journaled beginJournaled(UUID accountId, long generation, long correlationId, UUID characterId, String recipeId, UUID equipmentId, String dimension, int x, int y, int z, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"begin_precision".equals(envelope.operation())) throw new IllegalArgumentException("Invalid precision request.");
		var body = envelope.payload();
		if (body.size() != 2 || !recipeId.equals(text(body, "recipeId")) || !equipmentId.toString().equals(text(body, "equipmentId"))) throw new IllegalArgumentException("Invalid precision body.");
		var recipe = CraftCatalog.precision(recipeId);
		return mutate(accountId, correlationId, characterId, envelope, connection -> begin(connection, characterId, recipe, equipmentId, dimension, x, y, z));
	}

	public Journaled stepJournaled(UUID accountId, long generation, long correlationId, UUID characterId, UUID orderId, int stepIndex, String choice, String dimension, int x, int y, int z, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"step_precision".equals(envelope.operation())) throw new IllegalArgumentException("Invalid precision request.");
		var body = envelope.payload();
		if (body.size() != 3 || !orderId.toString().equals(text(body, "orderId")) || stepIndex != integer(body, "stepIndex") || !choice.equals(text(body, "choice"))) throw new IllegalArgumentException("Invalid precision body.");
		return mutate(accountId, correlationId, characterId, envelope, connection -> step(connection, characterId, orderId, stepIndex, choice, dimension, x, y, z));
	}

	private Journaled mutate(UUID accountId, long correlationId, UUID characterId, RequestEnvelope envelope, Change change) throws SQLException {
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, accountId, correlationId, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = change.apply(connection);
				outcome.addProperty("operation", envelope.operation());
				outcome.addProperty("characterId", characterId.toString());
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); if (exception instanceof SQLException sql) throw sql; if (exception instanceof RuntimeException runtime) throw runtime; throw new SQLException(exception); }
		}
	}

	private JsonObject begin(java.sql.Connection connection, UUID characterId, CraftCatalog.Precision recipe, UUID equipmentId, String dimension, int x, int y, int z) throws SQLException {
		ready(connection, characterId, recipe, dimension, x, y, z);
		var gear = gear(connection, characterId, equipmentId);
		if (!recipe.equipmentItem().equals(gear.itemId())) throw new IllegalArgumentException("Precision order is not ready.");
		try (var open = connection.prepareStatement("SELECT 1 FROM dalmaru.character_precision_orders WHERE character_id=? AND equipment_id=? AND state='OPEN' FOR UPDATE")) {
			open.setObject(1, characterId);
			open.setObject(2, equipmentId);
			try (var row = open.executeQuery()) { if (row.next()) throw new IllegalArgumentException("Precision order conflicts."); }
		}
		UUID orderId = UUID.randomUUID();
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_precision_orders(order_id, character_id, recipe_id, equipment_id, step_index, state) VALUES(?, ?, ?, ?, 0, 'OPEN')")) {
			insert.setObject(1, orderId);
			insert.setObject(2, characterId);
			insert.setString(3, recipe.id());
			insert.setObject(4, equipmentId);
			insert.executeUpdate();
		}
		return outcome(orderId, 0, false, equipmentId);
	}

	private JsonObject step(java.sql.Connection connection, UUID characterId, UUID orderId, int stepIndex, String choice, String dimension, int x, int y, int z) throws SQLException {
		UUID equipmentId;
		String recipeId;
		int current;
		String state;
		try (var query = connection.prepareStatement("SELECT recipe_id, equipment_id, step_index, state FROM dalmaru.character_precision_orders WHERE order_id=? AND character_id=? FOR UPDATE")) {
			query.setObject(1, orderId);
			query.setObject(2, characterId);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Unknown precision order.");
				recipeId = row.getString(1);
				equipmentId = row.getObject(2, UUID.class);
				current = row.getInt(3);
				state = row.getString(4);
			}
		}
		if (!"OPEN".equals(state) || stepIndex != current) throw new IllegalArgumentException("Precision step conflicts.");
		var recipe = CraftCatalog.precision(recipeId);
		ready(connection, characterId, recipe, dimension, x, y, z);
		gear(connection, characterId, equipmentId);
		var step = recipe.steps().get(current);
		if (!step.choice().equals(choice)) {
			mark(connection, orderId, current, choice, false, "FAILED");
			return outcome(orderId, current, false, equipmentId);
		}
		spend(connection, characterId, step.materialId(), step.binding(), step.quantity());
		boolean done = current + 1 == recipe.steps().size();
		mark(connection, orderId, done ? current + 1 : current + 1, choice, done, done ? "DONE" : "OPEN");
		return outcome(orderId, current + 1, done, equipmentId);
	}

	private void ready(java.sql.Connection connection, UUID characterId, CraftCatalog.Precision recipe, String dimension, int x, int y, int z) throws SQLException {
		try (var query = connection.prepareStatement("SELECT proficiency FROM dalmaru.character_life_professions WHERE character_id=? AND job=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, recipe.job());
			try (var row = query.executeQuery()) { if (!row.next() || row.getInt(1) < recipe.proficiency()) throw new IllegalArgumentException("Precision order is not ready."); }
		}
		if (!learned(connection, characterId, recipe.id()) && !learned(connection, characterId, recipe.prerequisiteRecipe())) throw new IllegalArgumentException("Precision prerequisite is not learned.");
		if (!recipe.stationId().isEmpty() && !CraftCatalog.station(recipe.stationId()).zone().contains(dimension, x, y, z)) throw new IllegalArgumentException("Precision order is not ready.");
	}

	private boolean learned(java.sql.Connection connection, UUID characterId, String recipeId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.character_learned_recipes WHERE character_id=? AND recipe_id=?")) {
			query.setObject(1, characterId);
			query.setString(2, recipeId);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private Gear gear(java.sql.Connection connection, UUID characterId, UUID equipmentId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT item_id, enhancement, binding FROM dalmaru.character_equipment WHERE equipment_id=? AND character_id=? FOR UPDATE")) {
			query.setObject(1, equipmentId);
			query.setObject(2, characterId);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Unknown equipment.");
				return new Gear(row.getString(1), row.getInt(2), row.getString(3));
			}
		}
	}

	private void mark(java.sql.Connection connection, UUID orderId, int stepIndex, String choice, boolean succeeded, String state) throws SQLException {
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_precision_orders SET step_index=?, choice=?, succeeded=?, state=? WHERE order_id=? AND state='OPEN'")) {
			update.setInt(1, stepIndex);
			update.setString(2, choice);
			if ("OPEN".equals(state)) update.setNull(3, java.sql.Types.BOOLEAN);
			else update.setBoolean(3, succeeded);
			update.setString(4, state);
			update.setObject(5, orderId);
			if (update.executeUpdate() != 1) throw new SQLException("Precision order update lost its row.");
		}
	}

	private void spend(java.sql.Connection connection, UUID characterId, String itemId, String binding, int quantity) throws SQLException {
		int owned = -1;
		String held = "";
		try (var query = connection.prepareStatement("SELECT quantity, binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, itemId);
			try (var row = query.executeQuery()) {
				if (row.next()) { owned = row.getInt(1); held = row.getString(2); }
			}
		}
		if (owned < quantity) throw new IllegalArgumentException("Insufficient material.");
		if (!held.equals(binding)) throw new IllegalArgumentException("Craft binding was rejected.");
		SupplyStay.refuseMove(connection, itemId, held);
		if (owned == quantity) {
			try (var delete = connection.prepareStatement("DELETE FROM dalmaru.character_materials WHERE character_id=? AND material_id=? AND binding=?")) {
				delete.setObject(1, characterId);
				delete.setString(2, itemId);
				delete.setString(3, held);
				if (delete.executeUpdate() != 1) throw new SQLException("Precision material update lost its row.");
			}
			return;
		}
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_materials SET quantity=? WHERE character_id=? AND material_id=? AND binding=?")) {
			update.setInt(1, owned - quantity);
			update.setObject(2, characterId);
			update.setString(3, itemId);
			update.setString(4, held);
			if (update.executeUpdate() != 1) throw new SQLException("Precision material update lost its row.");
		}
	}

	private static JsonObject outcome(UUID orderId, int step, boolean succeeded, UUID equipmentId) {
		var outcome = new JsonObject();
		outcome.addProperty("status", "OK");
		outcome.addProperty("orderId", orderId.toString());
		outcome.addProperty("step", step);
		outcome.addProperty("succeeded", succeeded);
		outcome.addProperty("equipmentId", equipmentId.toString());
		return outcome;
	}

	private static String text(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid precision body.");
		return body.get(key).getAsString();
	}

	private static int integer(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Invalid precision body.");
		try { return body.get(key).getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException exception) { throw new IllegalArgumentException("Invalid precision body."); }
	}

	private java.sql.Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	@FunctionalInterface private interface Change { JsonObject apply(java.sql.Connection connection) throws SQLException; }
}
