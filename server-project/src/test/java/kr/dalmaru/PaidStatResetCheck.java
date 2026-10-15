package kr.dalmaru;

import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;

public final class PaidStatResetCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a509");
	private PaidStatResetCheck() { }

	public static void main(String[] args) throws Exception {
		if (System.getenv("DALMARU_DB_NAME") == null || System.getenv("DALMARU_DB_NAME").isBlank()) throw new IllegalStateException("DALMARU_DB_NAME is required.");
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		try {
			var session = accounts.openSession(ACCOUNT);
			long generation = session.connectionGeneration();
			UUID id = characters.create(ACCOUNT, generation, 509, 1, "달마루_초기화").character().characterId();
			characters.selectActive(ACCOUNT, generation, 9, id);
			sql("UPDATE dalmaru.character_wallet SET copper=0 WHERE character_id=?", id);
			sql("UPDATE dalmaru.character_profiles SET strength=4, unspent_stat_points=0 WHERE character_id=?", id);
			var free = characters.resetStats(ACCOUNT, generation, 1, id);
			require(!free.replayed() && !free.profile().freeResetAvailable() && free.profile().strength() == 0 && free.profile().unspentStatPoints() == 4 && copper(id) == 0 && ledger(id) == 0, "Free reset charged copper.");
			sql("UPDATE dalmaru.character_profiles SET strength=2, unspent_stat_points=2 WHERE character_id=?", id);
			sql("UPDATE dalmaru.character_wallet SET copper=9 WHERE character_id=?", id);
			reject(() -> characters.resetStats(ACCOUNT, generation, 2, id));
			require(characters.profile(id).strength() == 2 && copper(id) == 9 && ledger(id) == 0, "Rejected paid reset changed stats or copper.");
			int cost = characters.profile(id).level() * CharacterRepository.PAID_RESET_COPPER_PER_LEVEL;
			sql("UPDATE dalmaru.character_wallet SET copper=30 WHERE character_id=?", id);
			var paid = characters.resetStats(ACCOUNT, generation, 3, id);
			require(!paid.replayed() && paid.profile().strength() == 0 && paid.profile().unspentStatPoints() == 4 && copper(id) == 30 - cost && ledger(id) == 1, "Paid reset did not refund only invested stats.");
			var replay = characters.resetStats(ACCOUNT, generation, 3, id);
			require(replay.replayed() && copper(id) == 30 - cost && ledger(id) == 1, "Paid reset replay charged again.");
			sql("UPDATE dalmaru.character_profiles SET level=5, strength=1, unspent_stat_points=0 WHERE character_id=?", id);
			sql("UPDATE dalmaru.character_wallet SET copper=? WHERE character_id=?", 5 * CharacterRepository.PAID_RESET_COPPER_PER_LEVEL, id);
			var higher = characters.resetStats(ACCOUNT, generation, 4, id);
			require(higher.profile().level() == 5 && higher.profile().unspentStatPoints() == 1 && copper(id) == 0 && ledger(id) == 2, "Higher level did not cost level times 10.");
			System.out.println("Paid stat reset keeps the first reset free and charges level times 10 PASS");
		} finally {
			cleanup();
		}
	}

	private static void reject(Attempt attempt) {
		try {
			attempt.run();
			throw new IllegalStateException("Underpaid reset was accepted.");
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains("Insufficient")) throw expected;
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void sql(String query, UUID id) throws Exception { sql(query, 0, id, false); }

	private static void sql(String query, int value, UUID id) throws Exception { sql(query, value, id, true); }

	private static void sql(String query, int value, UUID id, boolean withValue) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			if (withValue) { statement.setInt(1, value); statement.setObject(2, id); }
			else statement.setObject(1, id);
			statement.executeUpdate();
		}
	}

	private static int copper(UUID id) throws Exception { return scalar("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", id); }

	private static int ledger(UUID id) throws Exception { return scalar("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE character_id=? AND reason='stat_reset'", id); }

	private static int scalar(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup() throws Exception {
		try (var connection = connection()) {
			for (String query : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
				"DELETE FROM dalmaru.character_creation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_selection_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_stat_reset_requests WHERE account_id=?",
				"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_currency_ledger WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_supply_grants WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_equipment WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.characters WHERE account_id=?",
				"DELETE FROM dalmaru.accounts WHERE account_id=?"
			}) try (var statement = connection.prepareStatement(query)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); }
		}
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }

	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
