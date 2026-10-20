package kr.dalmaru.housing;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Server-clock crop growth. Gathering cooldown rows are not used. */
public final class CropRepository {
	public static final int GROW_HOURS = 8;
	public static final int MIN_HOURS = 4;
	private static final Set<String> CROPS = Set.of("grain", "vegetable", "herb", "fiber", "flower");
	private final HousingRepository homes = new HousingRepository();
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public Journaled plantJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !"plant_crop".equals(envelope.operation()) || payload.size() != 3 || !payload.has("housingId") || !payload.has("plot") || !payload.has("crop")) throw new IllegalArgumentException("Invalid crop request.");
		int plot = payload.get("plot").getAsInt();
		String crop = payload.get("crop").getAsString();
		if (plot < 0 || plot > 3 || !CROPS.contains(crop)) throw new IllegalArgumentException("Invalid crop request.");
		UUID housingId = UUID.fromString(payload.get("housingId").getAsString());
		return write(account, generation, correlation, character, envelope, connection -> plant(connection, account, housingId, plot, crop));
	}

	public Journaled tendJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !"tend_crop".equals(envelope.operation()) || payload.size() != 3 || !payload.has("housingId") || !payload.has("plot") || !payload.has("action")) throw new IllegalArgumentException("Invalid crop request.");
		String action = payload.get("action").getAsString();
		if (!"water".equals(action) && !"fertilize".equals(action)) throw new IllegalArgumentException("Invalid crop request.");
		int plot = payload.get("plot").getAsInt();
		if (plot < 0 || plot > 3) throw new IllegalArgumentException("Invalid crop request.");
		UUID housingId = UUID.fromString(payload.get("housingId").getAsString());
		return write(account, generation, correlation, character, envelope, connection -> tend(connection, account, housingId, plot));
	}

	public Journaled harvestJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !"harvest_crop".equals(envelope.operation()) || payload.size() != 3 || !payload.has("housingId") || !payload.has("plot") || !payload.has("generation")) throw new IllegalArgumentException("Invalid crop request.");
		int plot = payload.get("plot").getAsInt();
		int cropGeneration = payload.get("generation").getAsInt();
		if (plot < 0 || plot > 3 || cropGeneration < 1) throw new IllegalArgumentException("Invalid crop request.");
		UUID housingId = UUID.fromString(payload.get("housingId").getAsString());
		return write(account, generation, correlation, character, envelope, connection -> harvest(connection, account, character, housingId, plot, cropGeneration));
	}

	private Journaled write(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope, Step step) throws SQLException {
		if (correlation < 1 || envelope == null || !character.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid crop request.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, account, correlation, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = step.apply(connection);
				outcome.addProperty("operation", envelope.operation());
				outcome.addProperty("characterId", character.toString());
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception instanceof SQLException sql ? sql : new SQLException(exception); }
		}
	}

	private JsonObject plant(java.sql.Connection connection, UUID account, UUID housingId, int plot, String crop) throws SQLException {
		var outcome = new JsonObject();
		if (!allowed(connection, account, housingId, plot)) { outcome.addProperty("status", "FORBIDDEN"); return outcome; }
		if (cropRow(connection, housingId, plot) != null) { outcome.addProperty("status", "CONFLICT"); return outcome; }
		int generation = nextGeneration(connection, housingId, plot);
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.home_crops(housing_id, plot, crop, generation, planted_at, ready_at) VALUES(?, ?, ?, ?, now(), now() + interval '8 hours') RETURNING ready_at")) {
			insert.setObject(1, housingId);
			insert.setInt(2, plot);
			insert.setString(3, crop);
			insert.setInt(4, generation);
			try (var row = insert.executeQuery()) {
				row.next();
				outcome.addProperty("status", "OK");
				outcome.addProperty("crop", crop);
				outcome.addProperty("generation", generation);
				outcome.addProperty("readyAt", row.getTimestamp(1).toInstant().toString());
			}
		}
		return outcome;
	}

	private JsonObject tend(java.sql.Connection connection, UUID account, UUID housingId, int plot) throws SQLException {
		var outcome = new JsonObject();
		if (!allowed(connection, account, housingId, plot)) { outcome.addProperty("status", "FORBIDDEN"); return outcome; }
		try (var update = connection.prepareStatement("UPDATE dalmaru.home_crops SET ready_at=GREATEST(planted_at + interval '4 hours', ready_at - interval '4 hours') WHERE housing_id=? AND plot=? AND ready_at > planted_at + interval '4 hours' RETURNING ready_at")) {
			update.setObject(1, housingId);
			update.setInt(2, plot);
			try (var row = update.executeQuery()) {
				if (!row.next()) { outcome.addProperty("status", "NOT_READY"); return outcome; }
				outcome.addProperty("status", "OK");
				outcome.addProperty("readyAt", row.getTimestamp(1).toInstant().toString());
			}
		}
		return outcome;
	}

	private JsonObject harvest(java.sql.Connection connection, UUID account, UUID character, UUID housingId, int plot, int generation) throws SQLException {
		var outcome = new JsonObject();
		if (!allowed(connection, account, housingId, plot)) { outcome.addProperty("status", "FORBIDDEN"); return outcome; }
		var crop = cropRow(connection, housingId, plot);
		if (crop == null || crop.generation() != generation) {
			outcome.addProperty("status", harvested(connection, housingId, plot, generation, account) ? "DUPLICATE" : "NOT_READY");
			return outcome;
		}
		if (!crop.ready()) { outcome.addProperty("status", "NOT_READY"); return outcome; }
		if (harvested(connection, housingId, plot, generation, account)) { outcome.addProperty("status", "DUPLICATE"); return outcome; }
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.home_crop_harvests(housing_id, plot, generation, account_id) VALUES(?, ?, ?, ?)")) {
			insert.setObject(1, housingId);
			insert.setInt(2, plot);
			insert.setInt(3, generation);
			insert.setObject(4, account);
			insert.executeUpdate();
		}
		String material = "material." + crop.crop();
		int quantity = addMaterial(connection, character, material);
		int xp = addLifeXp(connection, character, 1);
		try (var remove = connection.prepareStatement("DELETE FROM dalmaru.home_crops WHERE housing_id=? AND plot=? AND generation=?")) {
			remove.setObject(1, housingId);
			remove.setInt(2, plot);
			remove.setInt(3, generation);
			if (remove.executeUpdate() != 1) throw new SQLException("Crop harvest lost its row.");
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("crop", crop.crop());
		outcome.addProperty("materialId", material);
		outcome.addProperty("quantity", quantity);
		outcome.addProperty("lifeXp", xp);
		outcome.addProperty("generation", generation + 1);
		return outcome;
	}

	private boolean allowed(java.sql.Connection connection, UUID account, UUID housingId, int plot) throws SQLException {
		if (!"OK".equals(homes.permit(account, housingId, "PLOT", "plot:" + plot))) return false;
		try (var lock = connection.prepareStatement("SELECT account_id FROM dalmaru.account_homes WHERE housing_id=? FOR UPDATE")) {
			lock.setObject(1, housingId);
			try (var row = lock.executeQuery()) {
				if (!row.next()) return false;
				if (account.equals(row.getObject(1, UUID.class))) return true;
			}
		}
		try (var grant = connection.prepareStatement("SELECT 1 FROM dalmaru.account_home_grants WHERE housing_id=? AND account_id=? AND permission='COLLAB' AND target_id=? AND revoked_at IS NULL")) {
			grant.setObject(1, housingId);
			grant.setObject(2, account);
			grant.setString(3, "plot:" + plot);
			try (var row = grant.executeQuery()) { return row.next(); }
		}
	}

	private Crop cropRow(java.sql.Connection connection, UUID housingId, int plot) throws SQLException {
		try (var query = connection.prepareStatement("SELECT crop, generation, ready_at <= clock_timestamp() FROM dalmaru.home_crops WHERE housing_id=? AND plot=? FOR UPDATE")) {
			query.setObject(1, housingId);
			query.setInt(2, plot);
			try (var row = query.executeQuery()) { return row.next() ? new Crop(row.getString(1), row.getInt(2), row.getBoolean(3)) : null; }
		}
	}

	private int nextGeneration(java.sql.Connection connection, UUID housingId, int plot) throws SQLException {
		try (var query = connection.prepareStatement("SELECT COALESCE(MAX(generation), 0) + 1 FROM dalmaru.home_crop_harvests WHERE housing_id=? AND plot=?")) {
			query.setObject(1, housingId);
			query.setInt(2, plot);
			try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private boolean harvested(java.sql.Connection connection, UUID housingId, int plot, int generation, UUID account) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.home_crop_harvests WHERE housing_id=? AND plot=? AND generation=? AND account_id=?")) {
			query.setObject(1, housingId);
			query.setInt(2, plot);
			query.setInt(3, generation);
			query.setObject(4, account);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private int addMaterial(java.sql.Connection connection, UUID character, String material) throws SQLException {
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, 1, 'FREE') ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=dalmaru.character_materials.quantity + 1 WHERE dalmaru.character_materials.binding='FREE' RETURNING quantity")) {
			update.setObject(1, character);
			update.setString(2, material);
			try (var row = update.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private int addLifeXp(java.sql.Connection connection, UUID character, int amount) throws SQLException {
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_life_xp(character_id, xp) VALUES(?, ?) ON CONFLICT(character_id) DO UPDATE SET xp=dalmaru.character_life_xp.xp + EXCLUDED.xp RETURNING xp")) {
			update.setObject(1, character);
			update.setInt(2, amount);
			try (var row = update.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private record Crop(String crop, int generation, boolean ready) { }

	@FunctionalInterface
	private interface Step { JsonObject apply(java.sql.Connection connection) throws SQLException; }
}
