package kr.dalmaru;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.ops.OperatorLedger;
import kr.dalmaru.request.RequestEnvelope;

public final class OperatorLedgerCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000012");
	private OperatorLedgerCheck() { }

	public static void main(String[] args) throws Exception {
		if (!"oscar_p1_fixture".equals(System.getenv("DALMARU_DB_NAME"))) throw new IllegalStateException("Refusing to run outside oscar_p1_fixture.");
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var ledger = new OperatorLedger();
		cleanup();
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("database", "migrations", "V027__operator_adjustments.sql")));
		}
		try {
			var session = accounts.openSession(ACCOUNT);
			long generation = session.connectionGeneration();
			var character = characters.create(ACCOUNT, 1, "ops_m12").characterId();
			characters.selectActive(ACCOUNT, generation, 1201, character);
			var grant = envelope(generation, character, "copper", null, 40, "보상");
			var applied = ledger.adjust(ACCOUNT, 1, grant);
			require(!applied.replayed() && applied.appliedBalance() == 40 && applied.currentBalance() == 40 && "보상".equals(applied.reason()) && currency(character, applied.eventId(), 40, "보상"), "Copper grant was not recorded.");
			var replay = ledger.adjust(ACCOUNT, 1, grant);
			require(replay.replayed() && replay.appliedBalance() == 40 && replay.currentBalance() == 40 && copper(character) == 40 && rows(ACCOUNT) == 1, "Copper grant was applied twice.");
			reject(() -> ledger.adjust(ACCOUNT, 2, envelope(generation, character, "copper", null, -100, "회수")));
			require(copper(character) == 40 && rows(ACCOUNT) == 1, "Over-revoke changed the balance.");
			reject(() -> ledger.adjust(ACCOUNT, 3, envelope(generation, character, "copper", null, 1, "  ")));
			reject(() -> ledger.adjust(ACCOUNT, 4, envelope(generation, character, "copper", null, 0, "보상")));
			var changed = new RequestEnvelope(grant.requestId(), generation, character, "operator_adjust", body("copper", null, 1, "보상"));
			reject(() -> ledger.adjust(ACCOUNT, 5, changed));
			require(copper(character) == 40 && rows(ACCOUNT) == 1, "Rejected request left a ledger row.");
			var later = ledger.adjust(ACCOUNT, 6, envelope(generation, character, "copper", null, 10, "추가"));
			var original = ledger.adjust(ACCOUNT, 1, grant);
			require(later.appliedBalance() == 50 && original.replayed() && original.appliedBalance() == 40 && original.currentBalance() == 50, "Replay hid the later balance.");
			var points = ledger.adjust(ACCOUNT, 7, envelope(generation, character, "skill_points", null, 5, "기술"));
			require(points.appliedBalance() == 5 && characters.profile(character).unspentSkillPoints() == 5, "Skill points were not granted.");
			reject(() -> ledger.adjust(ACCOUNT, 8, envelope(generation, character, "skill_points", null, -6, "기술회수")));
			require(characters.profile(character).unspentSkillPoints() == 5, "Skill point revoke went negative.");
			var material = ledger.adjust(ACCOUNT, 9, envelope(generation, character, "material", "ironstone", 3, "재료"));
			require(material.appliedBalance() == 3 && quantity(character, "ironstone") == 3 && "CHARACTER".equals(binding(character, "ironstone")), "Material grant was not recorded.");
			reject(() -> ledger.adjust(ACCOUNT, 10, envelope(generation, character, "material", "ironstone", -4, "재료회수")));
			require(quantity(character, "ironstone") == 3 && rows(ACCOUNT) == 4 && currencyRows(character) == 2, "Material revoke went negative.");
			require(audit(ACCOUNT, applied.requestId(), character, "copper", null, 40, "보상", 40), "Copper audit row is incomplete.");
			String granted = ledger.submit(ACCOUNT, generation, character, character, "copper", null, 1, 30, "명령");
			String replayed = ledger.submit(ACCOUNT, generation, character, character, "copper", null, 1, 30, "명령");
			String other = ledger.submit(ACCOUNT, generation, character, UUID.randomUUID(), "copper", null, 1, 31, "명령");
			require(granted.startsWith("처리했습니다.") && granted.contains("적용 잔액 51") && replayed.startsWith("이미 처리된 요청입니다.") && copper(character) == 51 && other.equals("대상은 현재 선택한 캐릭터여야 합니다."), "Operator command result was wrong.");
			System.out.println("Operator ledger verified.");
		} finally { cleanup(); }
	}

	private static RequestEnvelope envelope(long generation, UUID character, String kind, String itemId, int delta, String reason) {
		return new RequestEnvelope(UUID.randomUUID(), generation, character, "operator_adjust", body(kind, itemId, delta, reason));
	}

	private static JsonObject body(String kind, String itemId, int delta, String reason) {
		var payload = new JsonObject();
		payload.addProperty("kind", kind);
		if (itemId != null) payload.addProperty("itemId", itemId);
		payload.addProperty("delta", delta);
		payload.addProperty("reason", reason);
		return payload;
	}

	private static void reject(SqlCall call) {
		try { call.run(); throw new IllegalStateException("Invalid adjustment was accepted."); }
		catch (IllegalArgumentException expected) { }
		catch (Exception error) { throw new IllegalStateException(error); }
	}

	private static int copper(UUID character) throws Exception { return scalar("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", character, null); }
	private static int currencyRows(UUID character) throws Exception { return scalar("SELECT COUNT(*) FROM dalmaru.character_currency_ledger WHERE character_id=?", character, null); }
	private static boolean currency(UUID character, UUID event, int delta, String reason) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT source_run_id,reason,copper_delta FROM dalmaru.character_currency_ledger WHERE event_id=? AND character_id=?")) {
			statement.setObject(1, event); statement.setObject(2, character);
			try (var row = statement.executeQuery()) { return row.next() && row.getObject(1) == null && reason.equals(row.getString(2)) && row.getInt(3) == delta; }
		}
	}
	private static int quantity(UUID character, String item) throws Exception { return scalar("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?", character, item); }
	private static String binding(UUID character, String item) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			statement.setObject(1, character); statement.setString(2, item);
			try (var row = statement.executeQuery()) { if (!row.next()) throw new IllegalStateException("Material row is missing."); return row.getString(1); }
		}
	}
	private static int rows(UUID account) throws Exception { return scalar("SELECT COUNT(*) FROM dalmaru.operator_adjustments WHERE account_id=?", account, null); }
	private static boolean audit(UUID account, UUID request, UUID character, String kind, String item, int delta, String reason, int balance) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT character_id,kind,item_id,delta,reason,result_balance,created_at FROM dalmaru.operator_adjustments WHERE request_id=? AND account_id=?")) {
			statement.setObject(1, request); statement.setObject(2, account);
			try (var row = statement.executeQuery()) {
				return row.next() && character.equals(row.getObject(1, UUID.class)) && kind.equals(row.getString(2)) && (item == null ? row.getString(3) == null : item.equals(row.getString(3))) && row.getInt(4) == delta && reason.equals(row.getString(5)) && row.getInt(6) == balance && row.getTimestamp(7) != null;
			}
		}
	}
	private static int scalar(String sql, UUID id, String extra) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, id); if (extra != null) statement.setString(2, extra);
			try (var row = statement.executeQuery()) { if (!row.next()) throw new IllegalStateException("Expected row is missing."); return row.getInt(1); }
		}
	}
	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }

	private static void cleanup() throws Exception {
		if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return;
		try (var connection = connection()) {
			for (String sql : new String[] { "UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?", "DELETE FROM dalmaru.operator_adjustments WHERE account_id=?", "DELETE FROM dalmaru.operation_requests WHERE account_id=?", "DELETE FROM dalmaru.character_currency_ledger WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_selection_requests WHERE account_id=?", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.characters WHERE account_id=?", "DELETE FROM dalmaru.accounts WHERE account_id=?" }) {
				try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); }
			}
		} catch (java.sql.SQLException error) {
			if (!"42P01".equals(error.getSQLState())) throw error;
		}
	}

	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return java.sql.DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/oscar_p1_fixture", System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	@FunctionalInterface private interface SqlCall { void run() throws Exception; }
}
