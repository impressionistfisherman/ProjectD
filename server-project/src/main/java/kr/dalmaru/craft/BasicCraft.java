package kr.dalmaru.craft;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.account.SupplyStay;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Process ore and craft at a forge. Materials and the server position are judged here. */
public final class BasicCraft {
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public Journaled actJournaled(UUID accountId, long generation, long requestId, UUID characterId, String recipeId, String dimension, int x, int y, int z, RequestEnvelope envelope) throws SQLException {
		if (generation < 1 || requestId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid craft request.");
		if (!recipeId.equals(text(envelope.payload(), "recipeId"))) throw new IllegalArgumentException("Craft envelope does not match.");
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, accountId, requestId, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = craft(connection, characterId, recipeId, dimension, x, y, z);
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private JsonObject craft(java.sql.Connection connection, UUID characterId, String recipeId, String dimension, int x, int y, int z) throws SQLException {
		var recipe = CraftCatalog.recipe(recipeId);
		if (!recipe.stationId().isEmpty()) {
			var station = CraftCatalog.station(recipe.stationId());
			if (!station.zone().contains(dimension, x, y, z)) throw new IllegalArgumentException("Not at the craft station.");
		}
		var spent = new java.util.ArrayList<Held>();
		for (var input : recipe.inputs()) {
			Held row = lockMaterial(connection, characterId, input.itemId());
			if (row == null || row.quantity() < input.quantity()) throw new IllegalArgumentException("Insufficient material.");
			SupplyStay.refuseMove(connection, input.itemId(), row.binding());
			spent.add(new Held(input.itemId(), row.quantity() - input.quantity(), row.binding()));
		}
		var output = recipe.output();
		Held existing = lockMaterial(connection, characterId, output.itemId());
		if (existing != null && !existing.binding().equals(output.binding())) throw new IllegalArgumentException("Material binding does not match the recipe.");
		for (var row : spent) setMaterial(connection, characterId, row.itemId(), row.quantity(), row.binding());
		int owned = (existing == null ? 0 : existing.quantity()) + output.quantity();
		setMaterial(connection, characterId, output.itemId(), owned, output.binding());
		var outcome = new JsonObject();
		outcome.addProperty("recipeId", recipeId);
		outcome.addProperty("itemId", output.itemId());
		outcome.addProperty("owned", owned);
		return outcome;
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
				if (delete.executeUpdate() != 1) throw new IllegalArgumentException("Material binding does not match the recipe.");
			}
			return;
		}
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, ?) ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=EXCLUDED.quantity WHERE dalmaru.character_materials.binding=EXCLUDED.binding")) {
			update.setObject(1, characterId);
			update.setString(2, itemId);
			update.setInt(3, quantity);
			update.setString(4, binding);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Material binding does not match the recipe.");
		}
	}

	private static String text(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid craft body.");
		return body.get(key).getAsString();
	}

	private java.sql.Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	private record Held(String itemId, int quantity, String binding) { }
}
