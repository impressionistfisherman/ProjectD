package kr.dalmaru.housing;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** One home per account. Dungeon rows and open-world claims are not used. */
public final class HousingRepository {
	public static final String DIMENSION = "dalmaru:housing";
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }
	public record Snapshot(String status, UUID housingId, String dimension, int x, int y, int z, boolean inside) { }
	private record Spot(int x, int y, int z) { }

	public Journaled createJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		if (correlation < 1 || envelope == null || !character.equals(envelope.activeCharacterId()) || !"create_home".equals(envelope.operation()) || envelope.payload().size() != 0) throw new IllegalArgumentException("Invalid home request.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, account, correlation, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = create(connection, account, character);
				outcome.addProperty("operation", envelope.operation());
				outcome.addProperty("characterId", character.toString());
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception instanceof SQLException sql ? sql : new SQLException(exception); }
		}
	}

	public String permit(UUID account, UUID housingId, String action, String target) throws SQLException {
		ensureColumns();
		try (var connection = connect()) { return permit(connection, account, housingId, action, target); }
	}

	public Journaled grantJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !"grant_home".equals(envelope.operation()) || payload.size() < 3 || payload.size() > 4 || !payload.has("housingId") || !payload.has("accountId") || !payload.has("permission")) throw new IllegalArgumentException("Invalid home request.");
		UUID housingId = UUID.fromString(payload.get("housingId").getAsString());
		UUID targetAccount = UUID.fromString(payload.get("accountId").getAsString());
		String permission = payload.get("permission").getAsString();
		String targetId = payload.has("targetId") && !payload.get("targetId").isJsonNull() ? payload.get("targetId").getAsString() : null;
		if (!java.util.Set.of("VISIT", "INVITE", "COLLAB").contains(permission)) throw new IllegalArgumentException("Invalid home request.");
		if ("COLLAB".equals(permission) ? !named(targetId) : targetId != null) throw new IllegalArgumentException("Invalid home request.");
		return write(account, generation, correlation, character, envelope, connection -> grant(connection, account, housingId, targetAccount, permission, targetId));
	}

	public Journaled revokeJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !"revoke_home".equals(envelope.operation()) || payload.size() != 1 || !payload.has("grantId")) throw new IllegalArgumentException("Invalid home request.");
		UUID grantId = UUID.fromString(payload.get("grantId").getAsString());
		return write(account, generation, correlation, character, envelope, connection -> revoke(connection, account, grantId));
	}

	public Journaled placeJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		return change(account, generation, correlation, character, envelope, "place_home", "PLACE");
	}

	public Journaled expandJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		return change(account, generation, correlation, character, envelope, "expand_home", "EXPAND");
	}

	public Snapshot enter(UUID account, UUID character, UUID housingId, String returnDimension, int x, int y, int z) throws SQLException {
		return enter(account, character, housingId, returnDimension, x, y, z, false);
	}

	public Snapshot enter(UUID account, UUID character, UUID housingId, String returnDimension, int x, int y, int z, boolean combat) throws SQLException {
		if (housingId == null || returnDimension == null || returnDimension.isBlank() || DIMENSION.equals(returnDimension)) throw new IllegalArgumentException("Invalid home entry.");
		ensureColumns();
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				if (!member(connection, account, character) || !"OK".equals(permit(connection, account, housingId, "ENTER", null))) { connection.rollback(); return new Snapshot("FORBIDDEN", null, "", 0, 0, 0, false); }
				if (combat || inRun(connection, character)) { connection.rollback(); return new Snapshot("BUSY", housingId, "", 0, 0, 0, false); }
				var house = entrance(connection, housingId);
				try (var save = connection.prepareStatement("INSERT INTO dalmaru.account_home_presence(character_id, housing_id, inside, return_dimension, return_x, return_y, return_z) VALUES(?, ?, TRUE, ?, ?, ?, ?) ON CONFLICT (character_id) DO UPDATE SET housing_id=EXCLUDED.housing_id, inside=TRUE, return_dimension=EXCLUDED.return_dimension, return_x=EXCLUDED.return_x, return_y=EXCLUDED.return_y, return_z=EXCLUDED.return_z")) {
					save.setObject(1, character);
					save.setObject(2, housingId);
					save.setString(3, returnDimension);
					save.setInt(4, x);
					save.setInt(5, y);
					save.setInt(6, z);
					save.executeUpdate();
				}
				connection.commit();
				return new Snapshot("OK", housingId, DIMENSION, house.x(), house.y(), house.z(), true);
			} catch (Exception exception) { connection.rollback(); throw exception instanceof SQLException sql ? sql : new SQLException(exception); }
		}
	}

	public Snapshot leave(UUID account, UUID character) throws SQLException {
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				if (!member(connection, account, character)) { connection.rollback(); return new Snapshot("FORBIDDEN", null, "", 0, 0, 0, false); }
				Snapshot back = read(connection, character);
				if (back == null || !back.inside()) { connection.rollback(); return back == null ? new Snapshot("ABSENT", null, "", 0, 0, 0, false) : back; }
				try (var update = connection.prepareStatement("UPDATE dalmaru.account_home_presence SET inside=FALSE WHERE character_id=? AND inside=TRUE")) {
					update.setObject(1, character);
					if (update.executeUpdate() != 1) throw new SQLException("Home exit lost its row.");
				}
				connection.commit();
				return new Snapshot("OK", back.housingId(), back.dimension(), back.x(), back.y(), back.z(), false);
			} catch (Exception exception) { connection.rollback(); throw exception instanceof SQLException sql ? sql : new SQLException(exception); }
		}
	}

	public Snapshot recover(UUID character) throws SQLException {
		ensureColumns();
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				Snapshot stored = read(connection, character);
				if (stored == null) { connection.rollback(); return new Snapshot("ABSENT", null, "", 0, 0, 0, false); }
				if (!stored.inside()) { connection.rollback(); return stored; }
				var house = entrance(connection, stored.housingId());
				connection.commit();
				return new Snapshot("OK", stored.housingId(), DIMENSION, house.x(), house.y(), house.z(), true);
			} catch (Exception exception) { connection.rollback(); throw exception instanceof SQLException sql ? sql : new SQLException(exception); }
		}
	}

	public boolean claimBuild(UUID housingId) throws SQLException {
		ensureColumns();
		try (var connection = connect(); var update = connection.prepareStatement("UPDATE dalmaru.account_homes SET built=TRUE WHERE housing_id=? AND built=FALSE")) {
			update.setObject(1, housingId);
			return update.executeUpdate() == 1;
		}
	}

	private JsonObject create(java.sql.Connection connection, UUID account, UUID character) throws SQLException {
		ensureColumns();
		if (!member(connection, account, character)) throw new IllegalArgumentException("Invalid home request.");
		UUID existing = lockHome(connection, account);
		var outcome = new JsonObject();
		if (existing != null) {
			outcome.addProperty("status", "FULL");
			outcome.addProperty("housingId", existing.toString());
			return outcome;
		}
		UUID housingId = UUID.randomUUID();
		int parcel = nextParcel(connection);
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.account_homes(account_id, housing_id, dimension, parcel) VALUES(?, ?, ?, ?)")) {
			insert.setObject(1, account);
			insert.setObject(2, housingId);
			insert.setString(3, DIMENSION);
			insert.setInt(4, parcel);
			insert.executeUpdate();
		}
		try (var space = connection.prepareStatement("INSERT INTO dalmaru.account_home_spaces(housing_id, kind, plot_index, origin_x, origin_y, origin_z, size) VALUES(?, ?, ?, ?, 64, ?, ?)")) {
			add(space, housingId, "HOUSE", -1, 0, 0, 16);
			add(space, housingId, "PLOT", 0, 32, 0, 8);
			add(space, housingId, "PLOT", 1, 48, 0, 8);
			add(space, housingId, "PLOT", 2, 64, 0, 8);
			add(space, housingId, "PLOT", 3, 80, 0, 8);
			add(space, housingId, "WORKSHOP", -1, 0, 32, 16);
			space.executeBatch();
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("housingId", housingId.toString());
		return outcome;
	}

	private static void add(java.sql.PreparedStatement space, UUID housingId, String kind, int plot, int x, int z, int size) throws SQLException {
		space.setObject(1, housingId);
		space.setString(2, kind);
		space.setInt(3, plot);
		space.setInt(4, x);
		space.setInt(5, z);
		space.setInt(6, size);
		space.addBatch();
	}

	private UUID lockHome(java.sql.Connection connection, UUID account) throws SQLException {
		try (var query = connection.prepareStatement("SELECT housing_id FROM dalmaru.account_homes WHERE account_id=? FOR UPDATE")) {
			query.setObject(1, account);
			try (var row = query.executeQuery()) { return row.next() ? row.getObject(1, UUID.class) : null; }
		}
	}

	private boolean owns(java.sql.Connection connection, UUID account, UUID character, UUID housingId) throws SQLException {
		if (!member(connection, account, character)) return false;
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.account_homes WHERE account_id=? AND housing_id=?")) {
			query.setObject(1, account);
			query.setObject(2, housingId);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private boolean member(java.sql.Connection connection, UUID account, UUID character) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.characters WHERE character_id=? AND account_id=?")) {
			query.setObject(1, character);
			query.setObject(2, account);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private void ensureColumns() throws SQLException {
		try (var connection = connect(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE dalmaru.account_homes ADD COLUMN IF NOT EXISTS parcel INTEGER");
			statement.execute("ALTER TABLE dalmaru.account_homes ADD COLUMN IF NOT EXISTS built BOOLEAN NOT NULL DEFAULT FALSE");
			statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS account_homes_parcel ON dalmaru.account_homes(parcel) WHERE parcel IS NOT NULL");
			statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS account_home_grants_active ON dalmaru.account_home_grants (housing_id, account_id, permission, (COALESCE(target_id, ''))) WHERE revoked_at IS NULL");
			statement.execute("CREATE TABLE IF NOT EXISTS dalmaru.account_home_changes (housing_id UUID NOT NULL REFERENCES dalmaru.account_homes(housing_id) ON DELETE CASCADE, kind TEXT NOT NULL CHECK (kind IN ('PLACE', 'EXPAND')), change_key TEXT NOT NULL, size INTEGER NOT NULL CHECK (size > 0), PRIMARY KEY (housing_id, kind, change_key))");
		}
	}

	private int nextParcel(java.sql.Connection connection) throws SQLException {
		try (var lock = connection.createStatement()) { lock.execute("SELECT pg_advisory_xact_lock(4814)"); }
		try (var query = connection.createStatement(); var row = query.executeQuery("SELECT COALESCE(MAX(parcel), -1) + 1 FROM dalmaru.account_homes")) {
			row.next();
			return row.getInt(1);
		}
	}

	private Spot entrance(java.sql.Connection connection, UUID housingId) throws SQLException {
		int parcel = parcel(connection, housingId);
		return new Spot(HousingStructure.originX(parcel), 64, 0);
	}

	private int parcel(java.sql.Connection connection, UUID housingId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT parcel FROM dalmaru.account_homes WHERE housing_id=? FOR UPDATE")) {
			query.setObject(1, housingId);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new SQLException("Home row is missing.");
				if (row.getObject(1) != null) return row.getInt(1);
			}
		}
		int assigned = nextParcel(connection);
		try (var update = connection.prepareStatement("UPDATE dalmaru.account_homes SET parcel=? WHERE housing_id=? AND parcel IS NULL")) {
			update.setInt(1, assigned);
			update.setObject(2, housingId);
			if (update.executeUpdate() != 1) throw new SQLException("Home parcel was not stored.");
		}
		return assigned;
	}

	private boolean inRun(java.sql.Connection connection, UUID character) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.run_participants p JOIN dalmaru.run_instances r ON r.run_id=p.run_id WHERE p.character_id=? AND r.state IN ('ACTIVE', 'PAUSED') AND NOT EXISTS (SELECT 1 FROM dalmaru.run_member_lifecycle l WHERE l.run_id=p.run_id AND l.character_id=p.character_id AND l.left_at IS NOT NULL)")) {
			query.setObject(1, character);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private Spot spot(java.sql.Connection connection, UUID housingId, String kind, int plot) throws SQLException {
		try (var query = connection.prepareStatement("SELECT origin_x, origin_y, origin_z FROM dalmaru.account_home_spaces WHERE housing_id=? AND kind=? AND plot_index=?")) {
			query.setObject(1, housingId);
			query.setString(2, kind);
			query.setInt(3, plot);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new SQLException("Home space is missing.");
				return new Spot(row.getInt(1), row.getInt(2), row.getInt(3));
			}
		}
	}

	private Snapshot read(java.sql.Connection connection, UUID character) throws SQLException {
		try (var query = connection.prepareStatement("SELECT housing_id, inside, return_dimension, return_x, return_y, return_z FROM dalmaru.account_home_presence WHERE character_id=?")) {
			query.setObject(1, character);
			try (var row = query.executeQuery()) {
				if (!row.next()) return null;
				return new Snapshot(row.getBoolean(2) ? "INSIDE" : "OUTSIDE", row.getObject(1, UUID.class), row.getString(3), row.getInt(4), row.getInt(5), row.getInt(6), row.getBoolean(2));
			}
		}
	}

	private Journaled change(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope, String operation, String kind) throws SQLException {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !operation.equals(envelope.operation()) || payload.size() != 2 || !payload.has("housingId") || !payload.has("changeId")) throw new IllegalArgumentException("Invalid home request.");
		UUID housingId = UUID.fromString(payload.get("housingId").getAsString());
		String changeId = payload.get("changeId").getAsString();
		if (!named(changeId)) throw new IllegalArgumentException("Invalid home request.");
		return write(account, generation, correlation, character, envelope, connection -> change(connection, account, character, housingId, kind, changeId));
	}

	private Journaled write(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope, Step step) throws SQLException {
		if (correlation < 1 || envelope == null || !character.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid home request.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		ensureColumns();
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

	private JsonObject grant(java.sql.Connection connection, UUID account, UUID housingId, UUID targetAccount, String permission, String targetId) throws SQLException {
		var outcome = new JsonObject();
		if (!lockOwned(connection, account, housingId)) { outcome.addProperty("status", "NOT_OWNER"); return outcome; }
		if (account.equals(targetAccount) || !accountExists(connection, targetAccount)) { outcome.addProperty("status", "CONFLICT"); return outcome; }
		if (activeGrant(connection, housingId, targetAccount, permission, targetId) != null) { outcome.addProperty("status", "DUPLICATE"); return outcome; }
		UUID grantId = UUID.randomUUID();
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.account_home_grants(grant_id, housing_id, account_id, permission, target_id) VALUES(?, ?, ?, ?, ?)")) {
			insert.setObject(1, grantId);
			insert.setObject(2, housingId);
			insert.setObject(3, targetAccount);
			insert.setString(4, permission);
			insert.setString(5, targetId);
			insert.executeUpdate();
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("grantId", grantId.toString());
		outcome.addProperty("housingId", housingId.toString());
		outcome.addProperty("accountId", targetAccount.toString());
		outcome.addProperty("permission", permission);
		if (targetId != null) outcome.addProperty("targetId", targetId);
		return outcome;
	}

	private JsonObject revoke(java.sql.Connection connection, UUID account, UUID grantId) throws SQLException {
		var outcome = new JsonObject();
		UUID housingId = grantHome(connection, grantId);
		if (housingId == null || !lockOwned(connection, account, housingId)) { outcome.addProperty("status", "NOT_OWNER"); return outcome; }
		try (var update = connection.prepareStatement("UPDATE dalmaru.account_home_grants SET revoked_at=clock_timestamp() WHERE grant_id=? AND revoked_at IS NULL")) {
			update.setObject(1, grantId);
			if (update.executeUpdate() != 1) { outcome.addProperty("status", "CONFLICT"); return outcome; }
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("grantId", grantId.toString());
		outcome.addProperty("remaining", activeCount(connection, housingId));
		return outcome;
	}

	private JsonObject change(java.sql.Connection connection, UUID account, UUID character, UUID housingId, String kind, String changeId) throws SQLException {
		var outcome = new JsonObject();
		if (!lockOwned(connection, account, housingId)) { outcome.addProperty("status", "NOT_OWNER"); return outcome; }
		if (changeExists(connection, housingId, kind, changeId)) { outcome.addProperty("status", "CONFLICT"); return outcome; }
		if (!spendTimber(connection, character)) { outcome.addProperty("status", "INSUFFICIENT_MATERIAL"); return outcome; }
		int size = "EXPAND".equals(kind) ? currentSize(connection, housingId, changeId) + 4 : 1;
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.account_home_changes(housing_id, kind, change_key, size) VALUES(?, ?, ?, ?)")) {
			insert.setObject(1, housingId);
			insert.setString(2, kind);
			insert.setString(3, changeId);
			insert.setInt(4, size);
			insert.executeUpdate();
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("housingId", housingId.toString());
		outcome.addProperty("kind", kind);
		outcome.addProperty("changeId", changeId);
		outcome.addProperty("size", size);
		outcome.addProperty("spaces", spaceCount(connection, housingId));
		return outcome;
	}

	private String permit(java.sql.Connection connection, UUID account, UUID housingId, String action, String target) throws SQLException {
		if (account == null || housingId == null || action == null) throw new IllegalArgumentException("Invalid home access.");
		if (owner(connection, account, housingId)) return "DEMOLISH".equals(action) ? "FORBIDDEN" : "OK";
		if ("PLACE".equals(action) || "EXPAND".equals(action)) return "NOT_OWNER";
		if ("DEMOLISH".equals(action) || "STORAGE".equals(action) || "MATERIAL".equals(action) || "STALL".equals(action)) return "FORBIDDEN";
		boolean visit = false, invite = false, enter = false, named = false;
		try (var query = connection.prepareStatement("SELECT permission, target_id FROM dalmaru.account_home_grants WHERE housing_id=? AND account_id=? AND revoked_at IS NULL")) {
			query.setObject(1, housingId);
			query.setObject(2, account);
			try (var row = query.executeQuery()) {
				while (row.next()) {
					String permission = row.getString(1);
					String id = row.getString(2);
					if ("VISIT".equals(permission)) { visit = true; enter = true; }
					else if ("INVITE".equals(permission)) { invite = true; visit = true; enter = true; }
					else if ("COLLAB".equals(permission)) { enter = true; if (target != null && target.equals(id)) named = true; }
				}
			}
		}
		boolean allowed = switch (action) {
			case "ENTER" -> enter;
			case "SHOP" -> visit;
			case "FEATURE" -> invite;
			case "PLOT", "STATION" -> named;
			default -> throw new IllegalArgumentException("Invalid home access.");
		};
		return allowed ? "OK" : "FORBIDDEN";
	}

	private static boolean named(String targetId) {
		return targetId != null && (targetId.matches("plot:[0-3]") || targetId.matches("station:[a-z0-9_]{1,32}"));
	}

	private boolean lockOwned(java.sql.Connection connection, UUID account, UUID housingId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.account_homes WHERE housing_id=? AND account_id=? FOR UPDATE")) {
			query.setObject(1, housingId);
			query.setObject(2, account);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private boolean owner(java.sql.Connection connection, UUID account, UUID housingId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.account_homes WHERE housing_id=? AND account_id=?")) {
			query.setObject(1, housingId);
			query.setObject(2, account);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private boolean accountExists(java.sql.Connection connection, UUID account) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.accounts WHERE account_id=?")) {
			query.setObject(1, account);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private UUID activeGrant(java.sql.Connection connection, UUID housingId, UUID account, String permission, String targetId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT grant_id FROM dalmaru.account_home_grants WHERE housing_id=? AND account_id=? AND permission=? AND COALESCE(target_id, '')=COALESCE(?, '') AND revoked_at IS NULL")) {
			query.setObject(1, housingId);
			query.setObject(2, account);
			query.setString(3, permission);
			query.setString(4, targetId);
			try (var row = query.executeQuery()) { return row.next() ? row.getObject(1, UUID.class) : null; }
		}
	}

	private UUID grantHome(java.sql.Connection connection, UUID grantId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT housing_id FROM dalmaru.account_home_grants WHERE grant_id=?")) {
			query.setObject(1, grantId);
			try (var row = query.executeQuery()) { return row.next() ? row.getObject(1, UUID.class) : null; }
		}
	}

	private int activeCount(java.sql.Connection connection, UUID housingId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT count(*) FROM dalmaru.account_home_grants WHERE housing_id=? AND revoked_at IS NULL")) {
			query.setObject(1, housingId);
			try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private boolean spendTimber(java.sql.Connection connection, UUID character) throws SQLException {
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_materials SET quantity=quantity-1 WHERE character_id=? AND material_id='material.timber' AND binding='FREE' AND quantity>=1")) {
			update.setObject(1, character);
			return update.executeUpdate() == 1;
		}
	}

	private int currentSize(java.sql.Connection connection, UUID housingId, String changeId) throws SQLException {
		if (changeId.startsWith("plot:")) {
			try (var query = connection.prepareStatement("SELECT size FROM dalmaru.account_home_spaces WHERE housing_id=? AND kind='PLOT' AND plot_index=?")) {
				query.setObject(1, housingId);
				query.setInt(2, Integer.parseInt(changeId.substring(5)));
				try (var row = query.executeQuery()) { if (row.next()) return row.getInt(1); }
			}
		}
		return 16;
	}

	private boolean changeExists(java.sql.Connection connection, UUID housingId, String kind, String changeId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.account_home_changes WHERE housing_id=? AND kind=? AND change_key=?")) {
			query.setObject(1, housingId);
			query.setString(2, kind);
			query.setString(3, changeId);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private int spaceCount(java.sql.Connection connection, UUID housingId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT count(*) FROM dalmaru.account_home_spaces WHERE housing_id=?")) {
			query.setObject(1, housingId);
			try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	@FunctionalInterface
	private interface Step { JsonObject apply(java.sql.Connection connection) throws SQLException; }

	private java.sql.Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}
}
