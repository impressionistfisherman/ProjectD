package kr.dalmaru.craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.SupplyStay;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Learned recipes and research. Basic recipes and the enhancement ledger stay where they are. */
public final class CraftResearchRepository {
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }
	private record Line(String materialId, String binding, int quantity) { }
	private record Held(String itemId, int quantity, String binding) { }

	public Journaled researchJournaled(UUID accountId, long generation, long correlationId, UUID characterId, String clueId, String dimension, int x, int y, int z, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"research_craft".equals(envelope.operation())) throw new IllegalArgumentException("Invalid craft request.");
		var body = envelope.payload();
		if (body.size() != 2 || !clueId.equals(text(body, "clueId")) || !body.get("materials").isJsonArray()) throw new IllegalArgumentException("Invalid craft research.");
		var clue = CraftCatalog.clue(clueId);
		var lines = lines(body.getAsJsonArray("materials"));
		return mutate(accountId, correlationId, characterId, envelope, connection -> research(connection, characterId, clue, lines, dimension, x, y, z));
	}

	public Journaled craftJournaled(UUID accountId, long generation, long correlationId, UUID characterId, String recipeId, String dimension, int x, int y, int z, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"craft_learned".equals(envelope.operation())) throw new IllegalArgumentException("Invalid craft request.");
		var body = envelope.payload();
		if ((body.size() != 1 && body.size() != 2) || !recipeId.equals(text(body, "recipeId"))) throw new IllegalArgumentException("Invalid learned craft.");
		var recipe = CraftCatalog.learned(recipeId);
		String attribute = body.size() == 2 ? text(body, "attribute") : "";
		if (body.size() == 2 && (attribute.isBlank() || !attribute.equals(recipe.attribute()))) throw new IllegalArgumentException("Unknown craft attribute.");
		return mutate(accountId, correlationId, characterId, envelope, connection -> craft(connection, characterId, recipe, attribute, dimension, x, y, z));
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

	private JsonObject research(java.sql.Connection connection, UUID characterId, CraftCatalog.Clue clue, List<Line> lines, String dimension, int x, int y, int z) throws SQLException {
		ready(connection, characterId, clue.job(), clue.proficiency(), clue.stationId(), dimension, x, y, z);
		var picked = new ArrayList<CraftCatalog.Trial>();
		for (var line : lines) {
			var trial = trial(clue, line);
			if (trial == null) throw new IllegalArgumentException("Craft research did not narrow.");
			picked.add(trial);
		}
		var current = candidates(connection, characterId, clue);
		var next = new ArrayList<>(current);
		for (var trial : picked) for (var drop : trial.drops()) next.remove(drop);
		if (next.isEmpty() || next.size() >= current.size()) throw new IllegalArgumentException("Craft research did not narrow.");
		var ordered = new ArrayList<>(lines);
		ordered.sort(Comparator.comparing(Line::materialId));
		for (var line : ordered) spend(connection, characterId, line.materialId(), line.binding(), line.quantity());
		saveCandidates(connection, characterId, clue.id(), next);
		String learned = "";
		if (next.size() == 1) {
			learned = next.get(0);
			try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_learned_recipes(character_id, recipe_id) VALUES(?, ?)")) {
				insert.setObject(1, characterId);
				insert.setString(2, learned);
				insert.executeUpdate();
			}
		}
		var outcome = new JsonObject();
		outcome.addProperty("status", "OK");
		outcome.addProperty("candidates", next.size());
		if (!learned.isEmpty()) outcome.addProperty("learnedRecipeId", learned);
		outcome.add("materials", snapshot(connection, characterId, ordered));
		return outcome;
	}

	private JsonObject craft(java.sql.Connection connection, UUID characterId, CraftCatalog.Learned recipe, String attribute, String dimension, int x, int y, int z) throws SQLException {
		ready(connection, characterId, recipe.job(), recipe.proficiency(), recipe.stationId(), dimension, x, y, z);
		if (!learned(connection, characterId, recipe.id())) throw new IllegalArgumentException("Craft is not ready.");
		var inputs = new ArrayList<>(recipe.inputs());
		inputs.sort(Comparator.comparing(CraftCatalog.Input::itemId));
		String binding = recipe.output().binding();
		for (var input : inputs) {
			var held = lockMaterial(connection, characterId, input.itemId());
			if (held == null || held.quantity() < input.quantity()) throw new IllegalArgumentException("Insufficient material.");
			if (!held.binding().equals(binding) && rank(held.binding()) > rank(binding)) binding = held.binding();
			SupplyStay.refuseMove(connection, input.itemId(), held.binding());
			setMaterial(connection, characterId, input.itemId(), held.quantity() - input.quantity(), held.binding());
		}
		var outcome = new JsonObject();
		outcome.addProperty("status", "OK");
		outcome.addProperty("recipeId", recipe.id());
		outcome.addProperty("binding", binding);
		outcome.addProperty("attribute", attribute);
		if (recipe.equipment()) {
			UUID equipmentId = UUID.randomUUID();
			try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_equipment(equipment_id, character_id, slot_id, item_id, required_level, bound, location, binding, enhancement) VALUES(?, ?, NULL, ?, 1, ?, 'INVENTORY', ?, 0)")) {
				insert.setObject(1, equipmentId);
				insert.setObject(2, characterId);
				insert.setString(3, recipe.output().itemId());
				insert.setBoolean(4, !"FREE".equals(binding));
				insert.setString(5, binding);
				insert.executeUpdate();
			}
			outcome.addProperty("equipmentId", equipmentId.toString());
			outcome.addProperty("enhancement", 0);
		} else {
			var existing = lockMaterial(connection, characterId, recipe.output().itemId());
			if (existing != null && !existing.binding().equals(binding)) throw new IllegalArgumentException("Material binding does not match the recipe.");
			int owned = (existing == null ? 0 : existing.quantity()) + recipe.output().quantity();
			setMaterial(connection, characterId, recipe.output().itemId(), owned, binding);
			outcome.addProperty("itemId", recipe.output().itemId());
			outcome.addProperty("owned", owned);
		}
		kr.dalmaru.life.LifeProfessionRepository.grantAction(connection, characterId, recipe.job());
		return outcome;
	}

	private void ready(java.sql.Connection connection, UUID characterId, String job, int proficiency, String stationId, String dimension, int x, int y, int z) throws SQLException {
		try (var query = connection.prepareStatement("SELECT proficiency FROM dalmaru.character_life_professions WHERE character_id=? AND job=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, job);
			try (var row = query.executeQuery()) {
				if (!row.next() || row.getInt(1) < proficiency) throw new IllegalArgumentException("Craft is not ready.");
			}
		}
		if (!stationId.isEmpty() && !CraftCatalog.station(stationId).zone().contains(dimension, x, y, z)) throw new IllegalArgumentException("Craft is not ready.");
	}

	private static CraftCatalog.Trial trial(CraftCatalog.Clue clue, Line line) {
		CraftCatalog.Trial differentBinding = null;
		for (var trial : clue.trials()) {
			if (!trial.materialId().equals(line.materialId()) || trial.quantity() != line.quantity()) continue;
			if (trial.binding().equals(line.binding())) return trial;
			differentBinding = trial;
		}
		if (differentBinding != null) throw new IllegalArgumentException("Craft binding was rejected.");
		return null;
	}

	private List<String> candidates(java.sql.Connection connection, UUID characterId, CraftCatalog.Clue clue) throws SQLException {
		try (var query = connection.prepareStatement("SELECT candidates FROM dalmaru.character_craft_research WHERE character_id=? AND clue_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, clue.id());
			try (var row = query.executeQuery()) {
				if (!row.next()) return new ArrayList<>(clue.candidates());
				var stored = new ArrayList<String>();
				for (String candidate : row.getString(1).split(",")) if (!candidate.isBlank()) stored.add(candidate);
				return stored;
			}
		}
	}

	private void saveCandidates(java.sql.Connection connection, UUID characterId, String clueId, List<String> candidates) throws SQLException {
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_craft_research(character_id, clue_id, candidates) VALUES(?, ?, ?) ON CONFLICT(character_id, clue_id) DO UPDATE SET candidates=EXCLUDED.candidates")) {
			update.setObject(1, characterId);
			update.setString(2, clueId);
			update.setString(3, String.join(",", candidates));
			update.executeUpdate();
		}
	}

	private boolean learned(java.sql.Connection connection, UUID characterId, String recipeId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.character_learned_recipes WHERE character_id=? AND recipe_id=?")) {
			query.setObject(1, characterId);
			query.setString(2, recipeId);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private void spend(java.sql.Connection connection, UUID characterId, String itemId, String binding, int quantity) throws SQLException {
		var held = lockMaterial(connection, characterId, itemId);
		if (held == null || held.quantity() < quantity) throw new IllegalArgumentException("Insufficient material.");
		if (!held.binding().equals(binding)) throw new IllegalArgumentException("Craft binding was rejected.");
		SupplyStay.refuseMove(connection, itemId, held.binding());
		setMaterial(connection, characterId, itemId, held.quantity() - quantity, held.binding());
	}

	private JsonArray snapshot(java.sql.Connection connection, UUID characterId, List<Line> lines) throws SQLException {
		var materials = new JsonArray();
		for (var line : lines) {
			var held = lockMaterial(connection, characterId, line.materialId());
			var row = new JsonObject();
			row.addProperty("materialId", line.materialId());
			row.addProperty("quantity", held == null ? 0 : held.quantity());
			row.addProperty("binding", held == null ? line.binding() : held.binding());
			materials.add(row);
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

	private static int rank(String binding) {
		return switch (binding) {
			case "FREE" -> 0;
			case "ACCOUNT" -> 1;
			case "CHARACTER" -> 2;
			default -> throw new IllegalArgumentException("Craft binding was rejected.");
		};
	}

	private static List<Line> lines(JsonArray materials) {
		if (materials.size() < 1 || materials.size() > 8) throw new IllegalArgumentException("Invalid craft research.");
		var lines = new ArrayList<Line>();
		for (var entry : materials) {
			var line = entry.getAsJsonObject();
			if (line.size() != 3) throw new IllegalArgumentException("Invalid craft research.");
			String materialId = text(line, "materialId");
			String binding = text(line, "binding");
			int quantity = integer(line, "quantity");
			if (materialId.isBlank() || quantity < 1 || lines.stream().anyMatch(existing -> existing.materialId().equals(materialId))) throw new IllegalArgumentException("Invalid craft research.");
			lines.add(new Line(materialId, binding, quantity));
		}
		return lines;
	}

	private static String text(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid craft body.");
		return body.get(key).getAsString();
	}

	private static int integer(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Invalid craft body.");
		try { return body.get(key).getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException exception) { throw new IllegalArgumentException("Invalid craft body."); }
	}

	private java.sql.Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	@FunctionalInterface private interface Change { JsonObject apply(java.sql.Connection connection) throws SQLException; }
}
