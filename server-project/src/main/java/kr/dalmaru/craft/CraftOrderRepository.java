package kr.dalmaru.craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.SupplyStay;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Craft orders. Both accounts lock in id order before the order row. The market does not create these rows. */
public final class CraftOrderRepository {
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }
	private record Line(String materialId, String binding, int quantity) { }
	private record Order(UUID requesterAccount, UUID requesterCharacter, UUID crafterAccount, UUID crafterCharacter, String recipeId, int copper, String state, boolean expired) { }
	private record Parties(UUID requester, UUID crafter) { }

	public Journaled placeJournaled(UUID accountId, long generation, long correlationId, UUID characterId, String recipeId, int copper, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"place_craft_order".equals(envelope.operation())) throw new IllegalArgumentException("Invalid craft order.");
		var body = envelope.payload();
		if (body.size() != 3 || !recipeId.equals(text(body, "recipeId")) || copper != integer(body, "copper") || copper < 0 || !body.get("materials").isJsonArray()) throw new IllegalArgumentException("Invalid craft order.");
		var recipe = CraftCatalog.learned(recipeId);
		if (!recipe.equipment()) throw new IllegalArgumentException("Unknown recipe.");
		var lines = lines(body.getAsJsonArray("materials"), recipe);
		return mutate(accountId, null, correlationId, characterId, envelope, (connection, eventId) -> place(connection, accountId, characterId, recipe, lines, copper, eventId));
	}

	public Journaled acceptJournaled(UUID accountId, long generation, long correlationId, UUID characterId, UUID orderId, String dimension, int x, int y, int z, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"accept_craft_order".equals(envelope.operation()) || envelope.payload().size() != 1 || !orderId.toString().equals(text(envelope.payload(), "orderId"))) throw new IllegalArgumentException("Invalid craft order.");
		return mutate(accountId, peek(orderId).requester(), correlationId, characterId, envelope, (connection, eventId) -> accept(connection, accountId, characterId, orderId, dimension, x, y, z));
	}

	public Journaled settleJournaled(UUID accountId, long generation, long correlationId, UUID characterId, UUID orderId, String dimension, int x, int y, int z, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"settle_craft_order".equals(envelope.operation()) || envelope.payload().size() != 1 || !orderId.toString().equals(text(envelope.payload(), "orderId"))) throw new IllegalArgumentException("Invalid craft order.");
		return mutate(accountId, peek(orderId).requester(), correlationId, characterId, envelope, (connection, eventId) -> settle(connection, accountId, characterId, orderId, dimension, x, y, z, eventId));
	}

	public Journaled cancelJournaled(UUID accountId, long generation, long correlationId, UUID characterId, UUID orderId, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"cancel_craft_order".equals(envelope.operation()) || envelope.payload().size() != 1 || !orderId.toString().equals(text(envelope.payload(), "orderId"))) throw new IllegalArgumentException("Invalid craft order.");
		return mutate(accountId, peek(orderId).crafter(), correlationId, characterId, envelope, (connection, eventId) -> cancel(connection, accountId, characterId, orderId));
	}

	private Journaled mutate(UUID accountId, UUID other, long correlationId, UUID characterId, RequestEnvelope envelope, Change change) throws SQLException {
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				lockAccounts(connection, accountId, other);
				var stored = RequestJournal.begin(connection, accountId, correlationId, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = change.apply(connection, stored.receipt().eventId());
				outcome.addProperty("operation", envelope.operation());
				outcome.addProperty("characterId", characterId.toString());
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (SQLException exception) { connection.rollback(); throw exception; }
			catch (RuntimeException exception) { connection.rollback(); throw exception; }
			catch (Exception exception) { connection.rollback(); throw new SQLException(exception); }
		}
	}

	private JsonObject place(java.sql.Connection connection, UUID accountId, UUID characterId, CraftCatalog.Learned recipe, List<Line> lines, int copper, UUID eventId) throws SQLException {
		lockWallet(connection, characterId);
		String binding = recipe.output().binding();
		for (var line : lines) {
			var held = lockMaterial(connection, characterId, line.materialId());
			if (held == null || held.quantity() < line.quantity()) throw new IllegalArgumentException("Insufficient material.");
			if (!held.binding().equals(line.binding())) throw new IllegalArgumentException("Craft binding was rejected.");
			SupplyStay.refuseMove(connection, line.materialId(), held.binding());
			if (rank(held.binding()) > rank(binding)) binding = held.binding();
			setMaterial(connection, characterId, line.materialId(), held.quantity() - line.quantity(), held.binding());
		}
		moveCopper(connection, characterId, -copper, eventId, "order.lock");
		UUID orderId = UUID.randomUUID();
		Instant expires;
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_craft_orders(order_id, requester_account_id, requester_character_id, recipe_id, copper, state, created_at, expires_at, binding) VALUES(?, ?, ?, ?, ?, 'OPEN', clock_timestamp(), clock_timestamp() + interval '24 hours', ?) RETURNING expires_at")) {
			insert.setObject(1, orderId);
			insert.setObject(2, accountId);
			insert.setObject(3, characterId);
			insert.setString(4, recipe.id());
			insert.setInt(5, copper);
			insert.setString(6, binding);
			try (var row = insert.executeQuery()) { row.next(); expires = row.getTimestamp(1).toInstant(); }
		}
		try (var material = connection.prepareStatement("INSERT INTO dalmaru.character_craft_order_materials(order_id, material_id, binding, quantity) VALUES(?, ?, ?, ?)")) {
			for (var line : lines) {
				material.setObject(1, orderId);
				material.setString(2, line.materialId());
				material.setString(3, line.binding());
				material.setInt(4, line.quantity());
				material.addBatch();
			}
			material.executeBatch();
		}
		var outcome = new JsonObject();
		outcome.addProperty("status", "OK");
		outcome.addProperty("orderId", orderId.toString());
		outcome.addProperty("expiresAt", expires.toString());
		outcome.addProperty("binding", binding);
		return outcome;
	}

	private JsonObject accept(java.sql.Connection connection, UUID accountId, UUID characterId, UUID orderId, String dimension, int x, int y, int z) throws SQLException {
		var order = lockOrder(connection, orderId);
		if ("ACCEPTED".equals(order.state())) throw new IllegalArgumentException("Craft order is already accepted.");
		if (!"OPEN".equals(order.state())) throw new IllegalArgumentException("Craft order conflicts.");
		if (order.expired()) return finish(connection, order, orderId, "EXPIRED", "EXPIRED");
		if (accountId.equals(order.requesterAccount()) || !ready(connection, characterId, order.recipeId(), dimension, x, y, z)) throw new IllegalArgumentException("Craft order is not ready.");
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_craft_orders SET state='ACCEPTED', crafter_account_id=?, crafter_character_id=? WHERE order_id=? AND state='OPEN'")) {
			update.setObject(1, accountId);
			update.setObject(2, characterId);
			update.setObject(3, orderId);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Craft order conflicts.");
		}
		var outcome = new JsonObject();
		outcome.addProperty("status", "OK");
		outcome.addProperty("state", "ACCEPTED");
		outcome.addProperty("orderId", orderId.toString());
		return outcome;
	}

	private JsonObject settle(java.sql.Connection connection, UUID accountId, UUID characterId, UUID orderId, String dimension, int x, int y, int z, UUID eventId) throws SQLException {
		var order = lockOrder(connection, orderId);
		if ("SETTLED".equals(order.state()) || "CANCELLED".equals(order.state()) || "EXPIRED".equals(order.state())) throw new IllegalArgumentException("Craft order conflicts.");
		if (!"ACCEPTED".equals(order.state()) || !accountId.equals(order.crafterAccount()) || !characterId.equals(order.crafterCharacter())) throw new IllegalArgumentException("Craft order conflicts.");
		lockWallets(connection, order.requesterCharacter(), order.crafterCharacter());
		if (order.expired()) return finish(connection, order, orderId, "EXPIRED", "EXPIRED");
		if (!ready(connection, characterId, order.recipeId(), dimension, x, y, z)) return finish(connection, order, orderId, "CANCELLED", "NOT_READY");
		materials(connection, orderId);
		String binding = orderBinding(connection, orderId);
		var recipe = CraftCatalog.learned(order.recipeId());
		UUID equipmentId = UUID.randomUUID();
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_equipment(equipment_id, character_id, slot_id, item_id, required_level, bound, location, binding, enhancement) VALUES(?, ?, NULL, ?, 1, ?, 'INVENTORY', ?, 0)")) {
			insert.setObject(1, equipmentId);
			insert.setObject(2, order.requesterCharacter());
			insert.setString(3, recipe.output().itemId());
			insert.setBoolean(4, !"FREE".equals(binding));
			insert.setString(5, binding);
			insert.executeUpdate();
		}
		dropEscrow(connection, orderId);
		int fee = fee(order.copper());
		int payout = order.copper() - fee;
		addWallet(connection, order.crafterCharacter(), payout);
		if (order.copper() > 0) ledger(connection, order.crafterCharacter(), UUID.randomUUID(), "order.payout", order.copper());
		if (fee > 0) ledger(connection, order.crafterCharacter(), eventId, "ORDER_FEE", -fee);
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_craft_orders SET state='SETTLED', equipment_id=?, binding=?, fee=? WHERE order_id=? AND state='ACCEPTED'")) {
			update.setObject(1, equipmentId);
			update.setString(2, binding);
			update.setInt(3, fee);
			update.setObject(4, orderId);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Craft order conflicts.");
		}
		var outcome = new JsonObject();
		outcome.addProperty("status", "OK");
		outcome.addProperty("state", "SETTLED");
		outcome.addProperty("orderId", orderId.toString());
		outcome.addProperty("equipmentId", equipmentId.toString());
		outcome.addProperty("binding", binding);
		outcome.addProperty("fee", fee);
		outcome.addProperty("requesterCopper", copper(connection, order.requesterCharacter()));
		outcome.addProperty("crafterCopper", copper(connection, order.crafterCharacter()));
		return outcome;
	}

	private JsonObject cancel(java.sql.Connection connection, UUID accountId, UUID characterId, UUID orderId) throws SQLException {
		var order = lockOrder(connection, orderId);
		if (!accountId.equals(order.requesterAccount()) || !characterId.equals(order.requesterCharacter())) throw new IllegalArgumentException("Craft order conflicts.");
		if (!"OPEN".equals(order.state()) && !"ACCEPTED".equals(order.state())) throw new IllegalArgumentException("Craft order conflicts.");
		if (order.expired()) return finish(connection, order, orderId, "EXPIRED", "EXPIRED");
		return finish(connection, order, orderId, "CANCELLED", "CANCELLED");
	}

	private JsonObject finish(java.sql.Connection connection, Order order, UUID orderId, String state, String status) throws SQLException {
		lockWallet(connection, order.requesterCharacter());
		for (var line : materials(connection, orderId)) restore(connection, order.requesterCharacter(), line);
		dropEscrow(connection, orderId);
		moveCopper(connection, order.requesterCharacter(), order.copper(), UUID.randomUUID(), "order.return");
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_craft_orders SET state=? WHERE order_id=? AND state IN ('OPEN','ACCEPTED')")) {
			update.setString(1, state);
			update.setObject(2, orderId);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Craft order conflicts.");
		}
		var outcome = new JsonObject();
		outcome.addProperty("status", status);
		outcome.addProperty("state", state);
		outcome.addProperty("orderId", orderId.toString());
		outcome.addProperty("requesterCopper", copper(connection, order.requesterCharacter()));
		outcome.add("materials", snapshot(connection, order.requesterCharacter()));
		return outcome;
	}

	private boolean ready(java.sql.Connection connection, UUID characterId, String recipeId, String dimension, int x, int y, int z) throws SQLException {
		var recipe = CraftCatalog.learned(recipeId);
		try (var query = connection.prepareStatement("SELECT proficiency FROM dalmaru.character_life_professions WHERE character_id=? AND job=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, recipe.job());
			try (var row = query.executeQuery()) { if (!row.next() || row.getInt(1) < recipe.proficiency()) return false; }
		}
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.character_learned_recipes WHERE character_id=? AND recipe_id=?")) {
			query.setObject(1, characterId);
			query.setString(2, recipeId);
			try (var row = query.executeQuery()) { if (!row.next()) return false; }
		}
		return recipe.stationId().isEmpty() || CraftCatalog.station(recipe.stationId()).zone().contains(dimension, x, y, z);
	}

	private String orderBinding(java.sql.Connection connection, UUID orderId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT binding FROM dalmaru.character_craft_orders WHERE order_id=?")) {
			query.setObject(1, orderId);
			try (var row = query.executeQuery()) {
				if (!row.next() || row.getString(1) == null) throw new SQLException("Craft order lost its binding.");
				return row.getString(1);
			}
		}
	}

	private Parties peek(UUID orderId) throws SQLException {
		try (var connection = connect(); var query = connection.prepareStatement("SELECT requester_account_id, crafter_account_id FROM dalmaru.character_craft_orders WHERE order_id=?")) {
			query.setObject(1, orderId);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Unknown craft order.");
				return new Parties(row.getObject(1, UUID.class), row.getObject(2, UUID.class));
			}
		}
	}

	private Order lockOrder(java.sql.Connection connection, UUID orderId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT requester_account_id, requester_character_id, crafter_account_id, crafter_character_id, recipe_id, copper, state, expires_at <= clock_timestamp() FROM dalmaru.character_craft_orders WHERE order_id=? FOR UPDATE")) {
			query.setObject(1, orderId);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Unknown craft order.");
				return new Order(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getObject(3, UUID.class), row.getObject(4, UUID.class), row.getString(5), row.getInt(6), row.getString(7), row.getBoolean(8));
			}
		}
	}

	private void lockAccounts(java.sql.Connection connection, UUID left, UUID right) throws SQLException {
		if (left == null) throw new IllegalArgumentException("Unknown craft order account.");
		if (right == null || left.equals(right)) { lockAccount(connection, left); return; }
		UUID first = left.compareTo(right) <= 0 ? left : right;
		UUID second = first.equals(left) ? right : left;
		lockAccount(connection, first);
		lockAccount(connection, second);
	}

	private void lockAccount(java.sql.Connection connection, UUID accountId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT account_id FROM dalmaru.accounts WHERE account_id=? FOR UPDATE")) {
			query.setObject(1, accountId);
			try (var row = query.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Unknown craft order account."); }
		}
	}

	private void lockWallets(java.sql.Connection connection, UUID left, UUID right) throws SQLException {
		if (right == null || left.equals(right)) { lockWallet(connection, left); return; }
		UUID first = left.compareTo(right) <= 0 ? left : right;
		UUID second = first.equals(left) ? right : left;
		lockWallet(connection, first);
		lockWallet(connection, second);
	}

	private void lockWallet(java.sql.Connection connection, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT character_id FROM dalmaru.character_wallet WHERE character_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			try (var row = query.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Insufficient copper."); }
		}
	}

	private List<Line> materials(java.sql.Connection connection, UUID orderId) throws SQLException {
		var lines = new ArrayList<Line>();
		try (var query = connection.prepareStatement("SELECT material_id, binding, quantity FROM dalmaru.character_craft_order_materials WHERE order_id=? ORDER BY material_id FOR UPDATE")) {
			query.setObject(1, orderId);
			try (var row = query.executeQuery()) { while (row.next()) lines.add(new Line(row.getString(1), row.getString(2), row.getInt(3))); }
		}
		return lines;
	}

	private void dropEscrow(java.sql.Connection connection, UUID orderId) throws SQLException {
		try (var delete = connection.prepareStatement("DELETE FROM dalmaru.character_craft_order_materials WHERE order_id=?")) {
			delete.setObject(1, orderId);
			delete.executeUpdate();
		}
	}

	private void restore(java.sql.Connection connection, UUID characterId, Line line) throws SQLException {
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, ?) ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=dalmaru.character_materials.quantity+EXCLUDED.quantity WHERE dalmaru.character_materials.binding=EXCLUDED.binding")) {
			update.setObject(1, characterId);
			update.setString(2, line.materialId());
			update.setInt(3, line.quantity());
			update.setString(4, line.binding());
			if (update.executeUpdate() != 1) throw new SQLException("Craft order material restore lost its row.");
		}
	}

	private JsonArray snapshot(java.sql.Connection connection, UUID characterId) throws SQLException {
		var materials = new JsonArray();
		try (var query = connection.prepareStatement("SELECT material_id, quantity, binding FROM dalmaru.character_materials WHERE character_id=? ORDER BY material_id")) {
			query.setObject(1, characterId);
			try (var row = query.executeQuery()) {
				while (row.next()) {
					var line = new JsonObject();
					line.addProperty("materialId", row.getString(1));
					line.addProperty("quantity", row.getInt(2));
					line.addProperty("binding", row.getString(3));
					materials.add(line);
				}
			}
		}
		return materials;
	}

	private Held lockMaterial(java.sql.Connection connection, UUID characterId, String itemId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT quantity, binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, itemId);
			try (var row = query.executeQuery()) { return row.next() ? new Held(itemId, row.getInt(1), row.getString(2)) : null; }
		}
	}

	private void setMaterial(java.sql.Connection connection, UUID characterId, String itemId, int quantity, String binding) throws SQLException {
		if (quantity == 0) {
			try (var delete = connection.prepareStatement("DELETE FROM dalmaru.character_materials WHERE character_id=? AND material_id=? AND binding=?")) {
				delete.setObject(1, characterId);
				delete.setString(2, itemId);
				delete.setString(3, binding);
				if (delete.executeUpdate() != 1) throw new SQLException("Craft order material update lost its row.");
			}
			return;
		}
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_materials SET quantity=? WHERE character_id=? AND material_id=? AND binding=?")) {
			update.setInt(1, quantity);
			update.setObject(2, characterId);
			update.setString(3, itemId);
			update.setString(4, binding);
			if (update.executeUpdate() != 1) throw new SQLException("Craft order material update lost its row.");
		}
	}

	private void moveCopper(java.sql.Connection connection, UUID characterId, int delta, UUID eventId, String reason) throws SQLException {
		if (delta == 0) return;
		if (delta < 0) {
			try (var wallet = connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=copper-? WHERE character_id=? AND copper>=?")) {
				wallet.setInt(1, -delta);
				wallet.setObject(2, characterId);
				wallet.setInt(3, -delta);
				if (wallet.executeUpdate() != 1) throw new IllegalArgumentException("Insufficient copper.");
			}
		} else addWallet(connection, characterId, delta);
		ledger(connection, characterId, eventId, reason, delta);
	}

	private void addWallet(java.sql.Connection connection, UUID characterId, int delta) throws SQLException {
		if (delta == 0) return;
		if (delta < 0) throw new IllegalArgumentException("Insufficient copper.");
		try (var wallet = connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=copper+? WHERE character_id=?")) {
			wallet.setInt(1, delta);
			wallet.setObject(2, characterId);
			if (wallet.executeUpdate() != 1) throw new SQLException("Craft order payout lost its wallet.");
		}
	}

	private void ledger(java.sql.Connection connection, UUID characterId, UUID eventId, String reason, int delta) throws SQLException {
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id, character_id, source_run_id, reason, copper_delta) VALUES(?, ?, NULL, ?, ?)")) {
			insert.setObject(1, eventId);
			insert.setObject(2, characterId);
			insert.setString(3, reason);
			insert.setInt(4, delta);
			insert.executeUpdate();
		}
	}

	private int copper(java.sql.Connection connection, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?")) {
			query.setObject(1, characterId);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private static int fee(int copper) { return (int) ((copper * 2L + 50) / 100); }

	private static int rank(String binding) {
		return switch (binding) {
			case "FREE" -> 0;
			case "ACCOUNT" -> 1;
			case "CHARACTER" -> 2;
			default -> throw new IllegalArgumentException("Craft binding was rejected.");
		};
	}

	private static List<Line> lines(JsonArray materials, CraftCatalog.Learned recipe) {
		if (materials.size() != recipe.inputs().size() || materials.size() < 1 || materials.size() > 8) throw new IllegalArgumentException("Invalid craft order.");
		var lines = new ArrayList<Line>();
		for (var entry : materials) {
			var line = entry.getAsJsonObject();
			if (line.size() != 3) throw new IllegalArgumentException("Invalid craft order.");
			String materialId = text(line, "materialId");
			int quantity = integer(line, "quantity");
			var input = recipe.inputs().stream().filter(candidate -> candidate.itemId().equals(materialId) && candidate.quantity() == quantity).findFirst();
			if (input.isEmpty() || lines.stream().anyMatch(existing -> existing.materialId().equals(materialId))) throw new IllegalArgumentException("Invalid craft order.");
			lines.add(new Line(materialId, text(line, "binding"), quantity));
		}
		if (lines.size() != recipe.inputs().size()) throw new IllegalArgumentException("Invalid craft order.");
		lines.sort(Comparator.comparing(Line::materialId));
		return lines;
	}

	private static String text(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid craft order.");
		return body.get(key).getAsString();
	}

	private static int integer(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Invalid craft order.");
		try { return body.get(key).getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException exception) { throw new IllegalArgumentException("Invalid craft order."); }
	}

	private java.sql.Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	private record Held(String itemId, int quantity, String binding) { }
	@FunctionalInterface private interface Change { JsonObject apply(java.sql.Connection connection, UUID eventId) throws SQLException; }
}
