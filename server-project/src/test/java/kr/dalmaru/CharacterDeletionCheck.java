package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;

public final class CharacterDeletionCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a504");
	private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-00000000a514");
	private CharacterDeletionCheck() { }

	public static void main(String[] args) throws Exception {
		if (System.getenv("DALMARU_DB_NAME") == null || System.getenv("DALMARU_DB_NAME").isBlank()) throw new IllegalStateException("DALMARU_DB_NAME is required.");
		applyMigration();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		try {
			var session = accounts.openSession(ACCOUNT);
			var created = characters.create(ACCOUNT, session.connectionGeneration(), 504, 1, "달마루_삭제");
			UUID id = created.character().characterId();
			sql("UPDATE dalmaru.character_profiles SET free_reset_available=FALSE WHERE character_id=?", id);
			sql("INSERT INTO dalmaru.character_run_weekly_clears(character_id, content_id, difficulty, week_id, clear_count) VALUES(?, 'pilot.beacon', 'FIRST_STEP', DATE '2026-10-12', 2)", id);
			characters.selectActive(ACCOUNT, id);
			characters.delete(ACCOUNT, id);
			require(count("SELECT count(*) FROM dalmaru.accounts WHERE account_id=? AND active_character_id IS NULL", ACCOUNT) == 1, "Deleted character stayed active.");
			require(characters.list(ACCOUNT).stream().anyMatch(row -> row.characterId().equals(id)), "Deleted character left the slot.");
			reject(() -> characters.create(ACCOUNT, 1, "달마루_삭제2"));
			reject(() -> characters.create(ACCOUNT, 2, "달마루_삭제"));
			reject(() -> characters.selectActive(ACCOUNT, id));
			var marked = text("SELECT deleted_at::text FROM dalmaru.character_deletions WHERE character_id=?", id);
			characters.delete(ACCOUNT, id);
			require(marked.equals(text("SELECT deleted_at::text FROM dalmaru.character_deletions WHERE character_id=?", id)), "A second delete restarted the 7 days.");
			characters.restore(ACCOUNT, id);
			require(count("SELECT count(*) FROM dalmaru.character_deletions WHERE character_id=?", id) == 0, "Restore left the deletion.");
			require(!bool("SELECT free_reset_available FROM dalmaru.character_profiles WHERE character_id=?", id), "Restore reset the free reset flag.");
			require(count("SELECT clear_count FROM dalmaru.character_run_weekly_clears WHERE character_id=?", id) == 2, "Restore dropped the clear record.");
			characters.selectActive(ACCOUNT, id);
			characters.delete(ACCOUNT, id);
			sql("UPDATE dalmaru.character_deletions SET deleted_at=clock_timestamp() - interval '8 days' WHERE character_id=?", id);
			require(characters.list(ACCOUNT).isEmpty(), "Expired character still held the slot.");
			var again = characters.create(ACCOUNT, 1, "달마루_삭제");
			require(!id.equals(again.characterId()) && count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", again.characterId()) == 20, "Expired slot did not start a new character.");
			require(count("SELECT count(*) FROM dalmaru.characters WHERE character_id=?", id) == 0, "Expired character row remained.");
			var other = accounts.openSession(OTHER);
			var held = characters.create(OTHER, other.connectionGeneration(), 514, 1, "달마루_이름유지");
			characters.delete(OTHER, held.character().characterId());
			reject(() -> characters.create(ACCOUNT, 3, "달마루_이름유지"));
			System.out.println("Character deletion holds slot and name for 7 days, restore keeps records PASS");
		} finally {
			cleanup();
		}
	}

	private static void reject(Attempt attempt) {
		try {
			attempt.run();
			throw new IllegalStateException("Held character was accepted.");
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains("held") && !expected.getMessage().contains("pending")) throw expected;
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void applyMigration() throws Exception {
		try (var connection = connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=29")) {
			try (var result = check.executeQuery()) { if (result.next()) return; }
			try (var statement = connection.createStatement()) { statement.execute(Files.readString(Path.of("database", "migrations", "V029__character_deletions.sql"))); }
		}
	}

	private static void sql(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			statement.executeUpdate();
		}
	}

	private static int count(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static String text(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
		}
	}

	private static boolean bool(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { result.next(); return result.getBoolean(1); }
		}
	}

	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup() throws Exception {
		for (UUID account : new UUID[] { ACCOUNT, OTHER }) {
			try (var connection = connection()) {
				for (String query : new String[] {
					"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
					"DELETE FROM dalmaru.character_creation_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_selection_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_run_weekly_clears WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_supply_grants WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_equipment WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_deletions WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.characters WHERE account_id=?",
					"DELETE FROM dalmaru.accounts WHERE account_id=?"
				}) try (var statement = connection.prepareStatement(query)) { statement.setObject(1, account); statement.executeUpdate(); }
			}
		}
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }

	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
