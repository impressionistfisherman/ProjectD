package kr.dalmaru;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.quest.QuestStageRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class QuestStageCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a507");
	private static final QuestStageRepository.Place AWAY = new QuestStageRepository.Place("minecraft:overworld", 0, 64, 0, false);
	private static final QuestStageRepository.Place EDGE = new QuestStageRepository.Place("minecraft:overworld", 820, 64, 800, false);
	private static final QuestStageRepository.Place FIELD = new QuestStageRepository.Place("minecraft:overworld", 800, 64, 800, true);
	private static final QuestStageRepository.Place REPORT = new QuestStageRepository.Place("minecraft:overworld", 820, 64, 800, false);
	private QuestStageCheck() { }

	public static void main(String[] args) throws Exception {
		if (System.getenv("DALMARU_DB_NAME") == null || System.getenv("DALMARU_DB_NAME").isBlank()) throw new IllegalStateException("DALMARU_DB_NAME is required.");
		applyMigration();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var stages = new QuestStageRepository();
		try {
			var session = accounts.openSession(ACCOUNT);
			UUID lead = characters.create(ACCOUNT, 1, "달마루_조사").characterId();
			UUID partner = characters.create(ACCOUNT, 2, "달마루_현장").characterId();
			profession(lead);
			profession(partner);
			for (String stage : java.util.List.of("field", "evidence", "report")) reject(() -> stages.confirm(lead, stage, AWAY));
			reject(() -> stages.confirm(lead, "field", EDGE));
			require(!stages.confirmed(lead, "field") && !stages.confirmed(lead, "evidence") && !stages.confirmed(lead, "report") && count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", lead) == 20 && count("SELECT count(*) FROM dalmaru.character_supply_grants WHERE character_id=? AND grant_key='investigation'", lead) == 0, "Confirm without a server condition paid or confirmed.");
			reject(() -> stages.confirm(partner, "evidence", AWAY));
			stages.open(lead, "field");
			stages.fail(lead, "field");
			require(!stages.confirmed(lead, "field"), "A failed field stayed confirmed.");
			stages.open(lead, "field");
			stages.confirm(lead, "field", FIELD);
			stages.open(partner, "field");
			require(stages.confirmed(lead, "field") && stages.confirmed(partner, "field"), "Field stage was not shared.");
			reject(() -> stages.fail(partner, "field"));
			stages.confirm(lead, "evidence", AWAY);
			require(stages.confirmed(lead, "evidence") && !stages.confirmed(partner, "evidence"), "Evidence was shared.");
			reject(() -> stages.confirm(partner, "report", REPORT));
			stages.open(lead, "report");
			stages.fail(lead, "report");
			require(stages.confirmed(lead, "field") && stages.confirmed(lead, "evidence") && !stages.confirmed(lead, "report"), "Failure cleared a confirmed stage.");
			characters.selectActive(ACCOUNT, lead);
			var body = new JsonObject();
			body.addProperty("questId", QuestStageRepository.QUEST_ID);
			body.addProperty("stage", "report");
			body.addProperty("action", "confirm");
			var envelope = RequestEnvelope.legacy(ACCOUNT, session.connectionGeneration(), lead, "quest_stage", 507, body);
			var first = stages.actJournaled(ACCOUNT, session.connectionGeneration(), 507, lead, "report", "confirm", envelope, REPORT);
			var second = stages.actJournaled(ACCOUNT, session.connectionGeneration(), 507, lead, "report", "confirm", envelope, AWAY);
			require(!first.replayed() && first.result().get("rewarded").getAsBoolean() && second.replayed(), "Report confirm did not replay.");
			require(count("SELECT level FROM dalmaru.character_profiles WHERE character_id=?", lead) == 4 && count("SELECT experience FROM dalmaru.character_profiles WHERE character_id=?", lead) == 195, "Report experience was not granted once.");
			try (var connection = connection()) {
				connection.setAutoCommit(false);
				require(QuestStageRepository.reportConfirmed(connection, lead) && !QuestStageRepository.reportConfirmed(connection, partner), "Report completion was not readable on the caller connection.");
				connection.rollback();
			}
			require(count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", lead) == 60 && count("SELECT count(*) FROM dalmaru.character_supply_grants WHERE character_id=? AND grant_key='investigation'", lead) == 1, "Report reward was missing.");
			require(count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", partner) == 20 && count("SELECT count(*) FROM dalmaru.character_supply_grants WHERE character_id=? AND grant_key='investigation'", partner) == 0, "Partner received the personal report.");
			reject(() -> stages.confirm(partner, "evidence", AWAY));
			require(!stages.confirmed(partner, "evidence") && count("SELECT count(*) FROM dalmaru.character_supply_grants WHERE character_id=? AND grant_key='investigation'", partner) == 0, "Personal evidence was confirmed without a recorded fragment.");
			System.out.println("Quest stages keep confirmed work, share the field, and pay the personal report once PASS");
		} finally {
			cleanup();
		}
	}

	private static void reject(Attempt attempt) {
		try {
			attempt.run();
			throw new IllegalStateException("Quest stage accepted a blocked action.");
		} catch (IllegalArgumentException expected) {
			if (expected.getMessage() == null || expected.getMessage().isBlank()) throw expected;
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void profession(UUID character) throws Exception {
		try (var connection = connection(); var insert = connection.prepareStatement("INSERT INTO dalmaru.character_professions(character_id, base_job) VALUES(?, 'WARRIOR')")) {
			insert.setObject(1, character);
			insert.executeUpdate();
		}
	}

	private static void applyMigration() throws Exception {
		try (var connection = connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=30")) {
			try (var result = check.executeQuery()) { if (result.next()) return; }
			try (var statement = connection.createStatement()) { statement.execute(Files.readString(Path.of("database", "migrations", "V030__quest_stage_progress.sql"))); }
		}
	}

	private static int count(String sql, UUID character) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, character);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup() throws Exception {
		try (var connection = connection()) {
			for (String sql : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
				"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_creation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_selection_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_professions WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_supply_grants WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_equipment WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.quest_stage_progress WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.characters WHERE account_id=?",
				"DELETE FROM dalmaru.accounts WHERE account_id=?"
			}) try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); }
		}
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }

	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
