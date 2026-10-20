package kr.dalmaru.craft;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.housing.HousingRepository;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** One queued craft at a time per request. No repeat, automatic buy, or resale columns. */
public final class ProductionQueueRepository {
	private final HousingRepository homes = new HousingRepository();
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public Journaled enqueueJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !"enqueue_production".equals(envelope.operation()) || payload.size() != 3 || payload.has("repeat") || payload.has("autobuy") || payload.has("resale") || !payload.has("housingId") || !payload.has("recipeId") || !payload.has("quantity")) throw new IllegalArgumentException("Invalid production request.");
		int quantity = payload.get("quantity").getAsInt();
		if (quantity < 1 || quantity > 8) throw new IllegalArgumentException("Invalid production request.");
		UUID housingId = UUID.fromString(payload.get("housingId").getAsString());
		String recipeId = payload.get("recipeId").getAsString();
		return write(account, generation, correlation, character, envelope, connection -> enqueue(connection, account, character, housingId, recipeId, quantity));
	}

	public Journaled cancelJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		UUID jobId = jobId(envelope, "cancel_production");
		return write(account, generation, correlation, character, envelope, connection -> cancel(connection, account, character, jobId));
	}

	public Journaled claimJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		UUID jobId = jobId(envelope, "claim_production");
		return write(account, generation, correlation, character, envelope, connection -> claim(connection, account, character, jobId));
	}

	private static UUID jobId(RequestEnvelope envelope, String operation) {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !operation.equals(envelope.operation()) || payload.size() != 1 || !payload.has("jobId")) throw new IllegalArgumentException("Invalid production request.");
		return UUID.fromString(payload.get("jobId").getAsString());
	}

	private Journaled write(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope, Step step) throws SQLException {
		if (correlation < 1 || envelope == null || !character.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid production request.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		ensureTables();
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

	private JsonObject enqueue(java.sql.Connection connection, UUID account, UUID character, UUID housingId, String recipeId, int quantity) throws SQLException {
		var outcome = new JsonObject();
		if (recipeId.startsWith("task.") || CraftCatalog.queuesPrecision(recipeId)) { outcome.addProperty("status", "FORBIDDEN"); return outcome; }
		var recipe = CraftCatalog.basic(recipeId);
		if (recipe == null) throw new IllegalArgumentException("Invalid production request.");
		if (!workshop(connection, account, housingId)) { outcome.addProperty("status", "FORBIDDEN"); return outcome; }
		for (var input : recipe.inputs()) {
			int need = input.quantity() * quantity;
			try (var query = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=? AND binding='FREE' FOR UPDATE")) {
				query.setObject(1, character);
				query.setString(2, input.itemId());
				try (var row = query.executeQuery()) {
					if (!row.next() || row.getInt(1) < need) { outcome.addProperty("status", "INSUFFICIENT_MATERIAL"); return outcome; }
				}
			}
		}
		for (var input : recipe.inputs()) {
			try (var update = connection.prepareStatement("UPDATE dalmaru.character_materials SET quantity=quantity-? WHERE character_id=? AND material_id=? AND binding='FREE' AND quantity>=?")) {
				update.setInt(1, input.quantity() * quantity);
				update.setObject(2, character);
				update.setString(3, input.itemId());
				update.setInt(4, input.quantity() * quantity);
				if (update.executeUpdate() != 1) throw new SQLException("Production lock lost its material.");
			}
		}
		UUID jobId = UUID.randomUUID();
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.production_jobs(job_id, account_id, character_id, housing_id, recipe_id, quantity, ready_at, state) VALUES(?, ?, ?, ?, ?, ?, now() + interval '1 hour', 'QUEUED') RETURNING ready_at")) {
			insert.setObject(1, jobId);
			insert.setObject(2, account);
			insert.setObject(3, character);
			insert.setObject(4, housingId);
			insert.setString(5, recipeId);
			insert.setInt(6, quantity);
			try (var row = insert.executeQuery()) {
				row.next();
				outcome.addProperty("readyAt", row.getTimestamp(1).toInstant().toString());
			}
		}
		for (var input : recipe.inputs()) {
			try (var locked = connection.prepareStatement("INSERT INTO dalmaru.production_materials(job_id, material_id, quantity) VALUES(?, ?, ?)")) {
				locked.setObject(1, jobId);
				locked.setString(2, input.itemId());
				locked.setInt(3, input.quantity() * quantity);
				locked.executeUpdate();
			}
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("jobId", jobId.toString());
		outcome.addProperty("recipeId", recipeId);
		outcome.addProperty("quantity", quantity);
		return outcome;
	}

	private JsonObject cancel(java.sql.Connection connection, UUID account, UUID character, UUID jobId) throws SQLException {
		var outcome = new JsonObject();
		var job = lockJob(connection, jobId);
		if (job == null || !job.account().equals(account)) { outcome.addProperty("status", "FORBIDDEN"); return outcome; }
		if (!"QUEUED".equals(job.state()) || job.ready()) { outcome.addProperty("status", "CONFLICT"); return outcome; }
		restore(connection, character, jobId);
		try (var update = connection.prepareStatement("UPDATE dalmaru.production_jobs SET state='CANCELLED' WHERE job_id=? AND state='QUEUED'")) {
			update.setObject(1, jobId);
			if (update.executeUpdate() != 1) throw new SQLException("Production cancel lost its row.");
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("jobId", jobId.toString());
		return outcome;
	}

	private JsonObject claim(java.sql.Connection connection, UUID account, UUID character, UUID jobId) throws SQLException {
		var outcome = new JsonObject();
		var job = lockJob(connection, jobId);
		if (job == null || !job.account().equals(account)) { outcome.addProperty("status", "FORBIDDEN"); return outcome; }
		if ("CLAIMED".equals(job.state())) { outcome.addProperty("status", "DUPLICATE"); return outcome; }
		if (!"QUEUED".equals(job.state()) || !job.ready()) { outcome.addProperty("status", job.ready() ? "CONFLICT" : "NOT_READY"); return outcome; }
		var recipe = CraftCatalog.basic(job.recipeId());
		if (recipe == null) throw new SQLException("Queued recipe disappeared.");
		int made = recipe.output().quantity() * job.quantity();
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, 'FREE') ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=dalmaru.character_materials.quantity + EXCLUDED.quantity WHERE dalmaru.character_materials.binding='FREE' RETURNING quantity")) {
			update.setObject(1, character);
			update.setString(2, recipe.output().itemId());
			update.setInt(3, made);
			try (var row = update.executeQuery()) { row.next(); outcome.addProperty("quantity", row.getInt(1)); }
		}
		try (var finish = connection.prepareStatement("UPDATE dalmaru.production_jobs SET state='CLAIMED' WHERE job_id=? AND state='QUEUED'")) {
			finish.setObject(1, jobId);
			if (finish.executeUpdate() != 1) throw new SQLException("Production claim lost its row.");
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("jobId", jobId.toString());
		outcome.addProperty("itemId", recipe.output().itemId());
		return outcome;
	}

	private void restore(java.sql.Connection connection, UUID character, UUID jobId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT material_id, quantity FROM dalmaru.production_materials WHERE job_id=?")) {
			query.setObject(1, jobId);
			try (var row = query.executeQuery()) {
				while (row.next()) {
					try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, 'FREE') ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=dalmaru.character_materials.quantity + EXCLUDED.quantity WHERE dalmaru.character_materials.binding='FREE'")) {
						update.setObject(1, character);
						update.setString(2, row.getString(1));
						update.setInt(3, row.getInt(2));
						if (update.executeUpdate() != 1) throw new SQLException("Production return lost its material.");
					}
				}
			}
		}
	}

	private boolean workshop(java.sql.Connection connection, UUID account, UUID housingId) throws SQLException {
		if (!"OK".equals(homes.permit(account, housingId, "STATION", "station:forge"))) return false;
		try (var lock = connection.prepareStatement("SELECT account_id FROM dalmaru.account_homes WHERE housing_id=? FOR UPDATE")) {
			lock.setObject(1, housingId);
			try (var row = lock.executeQuery()) {
				if (!row.next()) return false;
				if (account.equals(row.getObject(1, UUID.class))) return true;
			}
		}
		try (var grant = connection.prepareStatement("SELECT 1 FROM dalmaru.account_home_grants WHERE housing_id=? AND account_id=? AND permission='COLLAB' AND target_id='station:forge' AND revoked_at IS NULL")) {
			grant.setObject(1, housingId);
			grant.setObject(2, account);
			try (var row = grant.executeQuery()) { return row.next(); }
		}
	}

	private Job lockJob(java.sql.Connection connection, UUID jobId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT account_id, recipe_id, quantity, state, ready_at <= clock_timestamp() FROM dalmaru.production_jobs WHERE job_id=? FOR UPDATE")) {
			query.setObject(1, jobId);
			try (var row = query.executeQuery()) {
				return row.next() ? new Job(row.getObject(1, UUID.class), row.getString(2), row.getInt(3), row.getString(4), row.getBoolean(5)) : null;
			}
		}
	}

	private void ensureTables() throws SQLException {
		try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
			statement.execute("CREATE TABLE IF NOT EXISTS dalmaru.production_jobs (job_id UUID PRIMARY KEY, account_id UUID NOT NULL REFERENCES dalmaru.accounts(account_id), character_id UUID NOT NULL REFERENCES dalmaru.characters(character_id), housing_id UUID NOT NULL REFERENCES dalmaru.account_homes(housing_id) ON DELETE CASCADE, recipe_id TEXT NOT NULL, quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 8), ready_at TIMESTAMPTZ NOT NULL, state TEXT NOT NULL CHECK (state IN ('QUEUED', 'CLAIMED', 'CANCELLED')))");
			statement.execute("CREATE TABLE IF NOT EXISTS dalmaru.production_materials (job_id UUID NOT NULL REFERENCES dalmaru.production_jobs(job_id) ON DELETE CASCADE, material_id TEXT NOT NULL, quantity INTEGER NOT NULL CHECK (quantity > 0), PRIMARY KEY (job_id, material_id))");
		}
	}

	private record Job(UUID account, String recipeId, int quantity, String state, boolean ready) { }

	@FunctionalInterface
	private interface Step { JsonObject apply(java.sql.Connection connection) throws SQLException; }
}
