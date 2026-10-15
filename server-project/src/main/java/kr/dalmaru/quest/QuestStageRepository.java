package kr.dalmaru.quest;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.account.StarterSupply;
import kr.dalmaru.account.TownZone;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Confirmed investigation stages stay. A failed segment restarts alone. The field stage is shared; evidence and the final report are personal. */
public final class QuestStageRepository {
	public static final String QUEST_ID = "pilot1.seal_investigation";
	public static final long REPORT_EXPERIENCE = 600;
	private static final List<String> ORDER = List.of("field", "evidence", "report");
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public record Place(String dimension, int x, int y, int z, boolean combat) { }

	public void open(UUID characterId, String stage) throws SQLException { inTransaction(connection -> apply(connection, characterId, stage, "open", null)); }

	public void confirm(UUID characterId, String stage, Place place) throws SQLException { inTransaction(connection -> apply(connection, characterId, stage, "confirm", place)); }

	public void fail(UUID characterId, String stage) throws SQLException { inTransaction(connection -> apply(connection, characterId, stage, "fail", null)); }

	public static boolean reportConfirmed(java.sql.Connection connection, UUID characterId) throws SQLException {
		if (connection == null || connection.getAutoCommit()) throw new IllegalArgumentException("Investigation report must be read in the caller's transaction.");
		try (var query = connection.prepareStatement("SELECT confirmed FROM dalmaru.quest_stage_progress WHERE character_id=? AND quest_id=? AND stage='report' FOR SHARE")) {
			query.setObject(1, characterId);
			query.setString(2, QUEST_ID);
			try (var row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
		}
	}

	public boolean confirmed(UUID characterId, String stage) throws SQLException {
		try (var connection = connect()) { return confirmed(connection, characterId, stage); }
	}

	public Journaled actJournaled(UUID accountId, long generation, long requestId, UUID characterId, String stage, String action, RequestEnvelope envelope, Place place) throws SQLException {
		if (generation < 1 || requestId < 1 || envelope == null || !characterId.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid quest stage request.");
		var body = envelope.payload();
		if (!QUEST_ID.equals(text(body, "questId")) || !stage.equals(text(body, "stage")) || !action.equals(text(body, "action"))) throw new IllegalArgumentException("Quest stage envelope does not match.");
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, accountId, requestId, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = apply(connection, characterId, stage, action, place);
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private JsonObject apply(java.sql.Connection connection, UUID characterId, String stage, String action, Place place) throws SQLException {
		int index = ORDER.indexOf(stage);
		if (index < 0 || (!action.equals("open") && !action.equals("confirm") && !action.equals("fail"))) throw new IllegalArgumentException("Unknown quest stage.");
		if ("fail".equals(action)) {
			if (confirmed(connection, characterId, stage)) throw new IllegalArgumentException("Confirmed stage is kept.");
			try (var delete = connection.prepareStatement("DELETE FROM dalmaru.quest_stage_progress WHERE character_id=? AND quest_id=? AND stage=? AND confirmed=FALSE")) {
				delete.setObject(1, characterId); delete.setString(2, QUEST_ID); delete.setString(3, stage);
				if (delete.executeUpdate() != 1) throw new IllegalArgumentException("Stage has not started.");
			}
			return outcome(stage, action, false, false);
		}
		if ("confirm".equals(action)) {
			if (index > 0 && !confirmed(connection, characterId, ORDER.get(index - 1))) throw new IllegalArgumentException("Earlier stage is not confirmed.");
			judge(connection, characterId, stage, place);
		}
		boolean already = confirmed(connection, characterId, stage);
		boolean now = "confirm".equals(action) || ("field".equals(stage) && anyConfirmed(connection, stage));
		try (var upsert = connection.prepareStatement("INSERT INTO dalmaru.quest_stage_progress(character_id,quest_id,stage,confirmed) VALUES(?,?,?,?) ON CONFLICT(character_id,quest_id,stage) DO UPDATE SET confirmed=TRUE WHERE EXCLUDED.confirmed AND NOT dalmaru.quest_stage_progress.confirmed")) {
			upsert.setObject(1, characterId); upsert.setString(2, QUEST_ID); upsert.setString(3, stage); upsert.setBoolean(4, now); upsert.executeUpdate();
		}
		if ("confirm".equals(action) && "field".equals(stage)) {
			try (var share = connection.prepareStatement("UPDATE dalmaru.quest_stage_progress SET confirmed=TRUE WHERE quest_id=? AND stage=? AND confirmed=FALSE")) {
				share.setString(1, QUEST_ID); share.setString(2, stage); share.executeUpdate();
			}
		}
		boolean rewarded = "confirm".equals(action) && "report".equals(stage) && !already;
		if (rewarded) {
			StarterSupply.grant(connection, characterId, StarterSupply.INVESTIGATION);
			CharacterRepository.grantExperience(connection, characterId, REPORT_EXPERIENCE);
		}
		return outcome(stage, action, confirmed(connection, characterId, stage), rewarded);
	}

	private static JsonObject outcome(String stage, String action, boolean confirmed, boolean rewarded) {
		var outcome = new JsonObject();
		outcome.addProperty("questId", QUEST_ID);
		outcome.addProperty("stage", stage);
		outcome.addProperty("action", action);
		outcome.addProperty("confirmed", confirmed);
		outcome.addProperty("rewarded", rewarded);
		return outcome;
	}

	private static String text(JsonObject body, String key) {
		if (!body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid quest stage body.");
		return body.get(key).getAsString();
	}

	private void judge(java.sql.Connection connection, UUID characterId, String stage, Place place) throws SQLException {
		var site = Site.current();
		if ("field".equals(stage)) {
			if (place == null || !site.fieldMet(place)) throw new IllegalArgumentException("Field condition is not met.");
			try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity) VALUES(?, ?, 1) ON CONFLICT DO NOTHING")) {
				insert.setObject(1, characterId);
				insert.setString(2, site.evidenceItem());
				insert.executeUpdate();
			}
		} else if ("evidence".equals(stage)) {
			try (var query = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
				query.setObject(1, characterId);
				query.setString(2, site.evidenceItem());
				try (var row = query.executeQuery()) {
					if (!row.next() || row.getInt(1) < 1) throw new IllegalArgumentException("Evidence has not been recorded.");
				}
			}
		} else if (place == null || !site.report().contains(place.dimension(), place.x(), place.y(), place.z())) throw new IllegalArgumentException("Report npc is out of range.");
	}

	private boolean confirmed(java.sql.Connection connection, UUID characterId, String stage) throws SQLException {
		try (var query = connection.prepareStatement("SELECT confirmed FROM dalmaru.quest_stage_progress WHERE character_id=? AND quest_id=? AND stage=?")) {
			query.setObject(1, characterId); query.setString(2, QUEST_ID); query.setString(3, stage);
			try (var row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
		}
	}

	private boolean anyConfirmed(java.sql.Connection connection, String stage) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.quest_stage_progress WHERE quest_id=? AND stage=? AND confirmed LIMIT 1")) {
			query.setString(1, QUEST_ID); query.setString(2, stage);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private void inTransaction(Work work) throws SQLException {
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try { work.apply(connection); connection.commit(); }
			catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private java.sql.Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	private interface Work { void apply(java.sql.Connection connection) throws SQLException; }

	private record Site(TownZone field, TownZone device, TownZone report, String evidenceItem) {
		private static final Site CURRENT = load();

		static Site current() { return CURRENT; }

		boolean fieldMet(Place place) {
			return field.contains(place.dimension(), place.x(), place.y(), place.z()) && (place.combat() || device.contains(place.dimension(), place.x(), place.y(), place.z()));
		}

		private static Site load() {
			try (var stream = QuestStageRepository.class.getResourceAsStream("/content/investigation.json")) {
				if (stream == null) throw new IllegalStateException("Missing investigation conditions");
				var json = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
				String dimension = json.get("dimension").getAsString();
				return new Site(zone(dimension, json.getAsJsonObject("field")), zone(dimension, json.getAsJsonObject("device")), zone(dimension, json.getAsJsonObject("report")), json.get("evidenceItem").getAsString());
			} catch (java.io.IOException exception) {
				throw new IllegalStateException("Cannot load investigation conditions", exception);
			}
		}

		private static TownZone zone(String dimension, JsonObject json) {
			return new TownZone(dimension, json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt(), json.get("radius").getAsInt());
		}
	}
}
