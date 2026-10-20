package kr.dalmaru.life;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Character life professions. Gather XP and combat stats stay on their own tables. */
public final class LifeProfessionRepository {
	public static final List<String> JOBS = List.of("blacksmith", "bowyer", "carpenter", "tailor", "lapidary", "cook", "alchemist", "apothecary", "charm_maker");
	private static final Set<String> JOB_SET = Set.copyOf(JOBS);
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Learned(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public Learned learnJournaled(UUID accountId, long generation, long correlationId, UUID characterId, String job, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"learn_life".equals(envelope.operation())) throw new IllegalArgumentException("Invalid life request.");
		var body = envelope.payload();
		if (body.size() != 1 || !job.equals(text(body, "job")) || !JOB_SET.contains(job)) throw new IllegalArgumentException("Unknown life job.");
		return mutate(accountId, correlationId, characterId, envelope, connection -> insertJob(connection, characterId, job));
	}

	public Learned gainJournaled(UUID accountId, long generation, long correlationId, UUID characterId, String job, int amount, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"gain_life".equals(envelope.operation())) throw new IllegalArgumentException("Invalid life request.");
		var body = envelope.payload();
		if (body.size() != 2 || !job.equals(text(body, "job")) || !JOB_SET.contains(job) || amount != integer(body, "amount") || amount < 1) throw new IllegalArgumentException("Invalid life gain.");
		return mutate(accountId, correlationId, characterId, envelope, connection -> addProficiency(connection, characterId, job, amount));
	}

	public Learned specializeJournaled(UUID accountId, long generation, long correlationId, UUID characterId, String job, String specialization, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"specialize_life".equals(envelope.operation())) throw new IllegalArgumentException("Invalid life request.");
		var body = envelope.payload();
		if (body.size() != 2 || !job.equals(text(body, "job")) || !specialization.equals(text(body, "specialization")) || !JOB_SET.contains(job)) throw new IllegalArgumentException("Unknown life job.");
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, accountId, correlationId, envelope);
				if (stored.result() != null) { connection.commit(); return new Learned(stored.result(), stored.receipt(), true); }
				lockProfile(connection, characterId);
				int spent = applySpecialization(connection, characterId, job, specialization, stored.receipt().eventId());
				var outcome = snapshot(connection, characterId, "OK");
				outcome.addProperty("operation", envelope.operation());
				outcome.addProperty("characterId", characterId.toString());
				outcome.addProperty("copperSpent", spent);
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Learned(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); if (exception instanceof SQLException sql) throw sql; if (exception instanceof RuntimeException runtime) throw runtime; throw new SQLException(exception); }
		}
	}

	public Learned completeTaskJournaled(UUID accountId, long generation, long correlationId, UUID characterId, String job, RequestEnvelope envelope) throws SQLException {
		if (correlationId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId()) || !"complete_life_task".equals(envelope.operation())) throw new IllegalArgumentException("Invalid life request.");
		var body = envelope.payload();
		if (body.size() != 1 || !job.equals(text(body, "job")) || !JOB_SET.contains(job)) throw new IllegalArgumentException("Unknown life job.");
		return mutate(accountId, correlationId, characterId, envelope, connection -> completeTask(connection, characterId, job));
	}

	public JsonObject profile(UUID accountId, long generation, UUID characterId) throws SQLException {
		try (var connection = connect()) {
			if (!characterId.equals(activeCharacter(connection, accountId, generation))) throw new IllegalArgumentException("Stale life session.");
			return snapshot(connection, characterId, "OK");
		}
	}

	private Learned mutate(UUID accountId, long correlationId, UUID characterId, RequestEnvelope envelope, Change change) throws SQLException {
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, accountId, correlationId, envelope);
				if (stored.result() != null) { connection.commit(); return new Learned(stored.result(), stored.receipt(), true); }
				lockProfile(connection, characterId);
				change.apply(connection);
				var outcome = snapshot(connection, characterId, "OK");
				outcome.addProperty("operation", envelope.operation());
				outcome.addProperty("characterId", characterId.toString());
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Learned(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); if (exception instanceof SQLException sql) throw sql; if (exception instanceof RuntimeException runtime) throw runtime; throw new SQLException(exception); }
		}
	}

	private void insertJob(java.sql.Connection connection, UUID characterId, String job) throws SQLException {
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_life_professions(character_id, job, proficiency) VALUES(?, ?, 1)")) {
			update.setObject(1, characterId);
			update.setString(2, job);
			try { update.executeUpdate(); }
			catch (SQLException error) { if ("23505".equals(error.getSQLState())) throw new IllegalArgumentException("Life job is already learned."); throw error; }
		}
	}

	private void addProficiency(java.sql.Connection connection, UUID characterId, String job, int amount) throws SQLException {
		try (var query = connection.prepareStatement("SELECT proficiency FROM dalmaru.character_life_professions WHERE character_id=? AND job=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, job);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Life job is not learned.");
				int next = Math.addExact(row.getInt(1), amount);
				if (next > 100) throw new IllegalArgumentException("Life proficiency is already at the cap.");
				try (var update = connection.prepareStatement("UPDATE dalmaru.character_life_professions SET proficiency=? WHERE character_id=? AND job=?")) {
					update.setInt(1, next);
					update.setObject(2, characterId);
					update.setString(3, job);
					if (update.executeUpdate() != 1) throw new SQLException("Life proficiency update lost its row.");
				}
			}
		}
	}

	private int applySpecialization(java.sql.Connection connection, UUID characterId, String job, String specialization, UUID eventId) throws SQLException {
		int required = tier(job, specialization);
		try (var query = connection.prepareStatement("SELECT proficiency, specialization FROM dalmaru.character_life_professions WHERE character_id=? AND job=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, job);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Life job is not learned.");
				int proficiency = row.getInt(1);
				String current = row.getString(2);
				if (proficiency < required) throw new IllegalArgumentException("Life specialization is not ready.");
				if (specialization.equals(current)) throw new IllegalArgumentException("Life specialization is already chosen.");
				int cost = current == null ? 0 : 500;
				if (cost > 0) charge(connection, characterId, cost, eventId);
				try (var update = connection.prepareStatement("UPDATE dalmaru.character_life_professions SET specialization=?, first_specialization_used=TRUE WHERE character_id=? AND job=?")) {
					update.setString(1, specialization);
					update.setObject(2, characterId);
					update.setString(3, job);
					if (update.executeUpdate() != 1) throw new SQLException("Life specialization update lost its row.");
				}
				return cost;
			}
		}
	}

	private void completeTask(java.sql.Connection connection, UUID characterId, String job) throws SQLException {
		try (var query = connection.prepareStatement("SELECT specialization, task_state FROM dalmaru.character_life_professions WHERE character_id=? AND job=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, job);
			try (var row = query.executeQuery()) {
				if (!row.next() || !(job + ".grandmaster").equals(row.getString(1))) throw new IllegalArgumentException("Life task is not ready.");
				if ("done".equals(row.getString(2))) throw new IllegalArgumentException("Life task is already done.");
				try (var update = connection.prepareStatement("UPDATE dalmaru.character_life_professions SET task_state='done' WHERE character_id=? AND job=?")) {
					update.setObject(1, characterId);
					update.setString(2, job);
					if (update.executeUpdate() != 1) throw new SQLException("Life task update lost its row.");
				}
			}
		}
	}

	private void charge(java.sql.Connection connection, UUID characterId, int cost, UUID eventId) throws SQLException {
		try (var wallet = connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=copper-? WHERE character_id=? AND copper>=?")) {
			wallet.setInt(1, cost);
			wallet.setObject(2, characterId);
			wallet.setInt(3, cost);
			if (wallet.executeUpdate() != 1) throw new IllegalArgumentException("Insufficient copper.");
		}
		try (var ledger = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id, character_id, source_run_id, reason, copper_delta) VALUES(?, ?, NULL, 'life_specialization', ?)")) {
			ledger.setObject(1, eventId);
			ledger.setObject(2, characterId);
			ledger.setInt(3, -cost);
			ledger.executeUpdate();
		}
	}

	private static int tier(String job, String specialization) {
		String prefix = job + ".";
		if (!specialization.startsWith(prefix)) throw new IllegalArgumentException("Unknown life specialization.");
		return switch (specialization.substring(prefix.length())) {
			case "journeyman" -> 21;
			case "expert" -> 41;
			case "master" -> 61;
			case "grandmaster" -> 81;
			default -> throw new IllegalArgumentException("Unknown life specialization.");
		};
	}

	private void lockProfile(java.sql.Connection connection, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT character_id FROM dalmaru.character_profiles WHERE character_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			try (var row = query.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Character profile was not found."); }
		}
	}

	private UUID activeCharacter(java.sql.Connection connection, UUID accountId, long generation) throws SQLException {
		try (var query = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=?")) {
			query.setObject(1, accountId);
			query.setLong(2, generation);
			try (var row = query.executeQuery()) {
				if (!row.next() || row.getObject(1) == null) throw new IllegalArgumentException("Stale life session.");
				return row.getObject(1, UUID.class);
			}
		}
	}

	private JsonObject snapshot(java.sql.Connection connection, UUID characterId, String status) throws SQLException {
		var jobs = new JsonArray();
		try (var query = connection.prepareStatement("SELECT job, proficiency, COALESCE(specialization, ''), renown, first_specialization_used FROM dalmaru.character_life_professions WHERE character_id=? ORDER BY job")) {
			query.setObject(1, characterId);
			try (var row = query.executeQuery()) {
				while (row.next()) {
					var job = new JsonObject();
					job.addProperty("job", row.getString(1));
					job.addProperty("proficiency", row.getInt(2));
					job.addProperty("specialization", row.getString(3));
					job.addProperty("renown", row.getInt(4));
					job.addProperty("firstSpecializationUsed", row.getBoolean(5));
					jobs.add(job);
				}
			}
		}
		int lifeXp = 0;
		try (var query = connection.prepareStatement("SELECT xp FROM dalmaru.character_life_xp WHERE character_id=?")) {
			query.setObject(1, characterId);
			try (var row = query.executeQuery()) { if (row.next()) lifeXp = row.getInt(1); }
		}
		var outcome = new JsonObject();
		outcome.addProperty("status", status);
		outcome.addProperty("lifeXp", lifeXp);
		outcome.add("jobs", jobs);
		return outcome;
	}

	private java.sql.Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	private static String text(JsonObject body, String key) {
		if (!body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid life field.");
		return body.get(key).getAsString();
	}

	private static int integer(JsonObject body, String key) {
		if (!body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Invalid life field.");
		try { return body.get(key).getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException error) { throw new IllegalArgumentException("Invalid life field.", error); }
	}

	@FunctionalInterface private interface Change { void apply(java.sql.Connection connection) throws SQLException; }
}
