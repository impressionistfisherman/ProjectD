package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.ops.OperatorLedger;

public final class OperatorLedgerCheck {
	private static final UUID OPERATOR = UUID.fromString("00000000-0000-4000-8000-000000000012");
	private static final UUID TARGET = UUID.fromString("00000000-0000-4000-8000-000000000013");
	private OperatorLedgerCheck() { }

	public static void main(String[] args) throws Exception {
		if (!"oscar_p2_fixture".equals(System.getenv("DALMARU_DB_NAME"))) throw new IllegalStateException("Refusing to run outside oscar_p2_fixture.");
		String network = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
		int hook = network.indexOf("OperatorAdjustCommand.command");
		int next = network.indexOf("dalmaru-town", hook);
		if (hook < 0 || next < 0 || !network.substring(hook, next).contains("sendCharacterProfile")) throw new IllegalStateException("Online target is not refreshed through the existing profile send.");
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var ledger = new OperatorLedger();
		try (var connection = connection(); var statement = connection.createStatement()) { statement.execute("DROP TABLE IF EXISTS dalmaru.operator_adjustments"); }
		cleanup();
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("database", "migrations", "V027__operator_adjustments.sql")));
		}
		try {
			accounts.openSession(OPERATOR);
			accounts.openSession(TARGET);
			characters.create(OPERATOR, 1, "ops_m12");
			UUID character = characters.create(TARGET, 1, "ops_m12b").characterId();
			var applied = ledger.adjust(OPERATOR, character, "copper", null, 40, 1, "보상");
			require(!applied.replayed() && applied.appliedBalance() == 40 && OPERATOR.equals(applied.operatorAccountId()) && TARGET.equals(applied.targetAccountId()) && currency(character, applied.eventId(), 40, "보상") && audit(OPERATOR, 1, TARGET, character), "Other character grant was not separated.");
			var replay = ledger.adjust(OPERATOR, character, "copper", null, 40, 1, "보상");
			require(replay.replayed() && replay.appliedBalance() == 40 && copper(character) == 40, "Copper grant was applied twice.");
			reject(() -> ledger.adjust(OPERATOR, character, "copper", null, 1, 1, "다른내용"));
			reject(() -> ledger.adjust(OPERATOR, character, "copper", null, -100, 2, "회수"));
			require(copper(character) == 40, "Over-revoke changed the balance.");
			reject(() -> ledger.adjust(OPERATOR, character, "copper", null, 1, 3, "  "));
			var points = ledger.adjust(OPERATOR, character, "skill_points", null, 5, 4, "기술");
			require(points.appliedBalance() == 5 && characters.profile(character).unspentSkillPoints() == 5, "Skill points were not granted.");
			reject(() -> ledger.adjust(OPERATOR, character, "skill_points", null, -6, 5, "기술회수"));
			var material = ledger.adjust(OPERATOR, character, "material", "ironstone", 3, 6, "재료");
			require(material.appliedBalance() == 3 && quantity(character, "ironstone") == 3, "Material grant was not recorded.");
			String command = ledger.submit(OPERATOR, character, "copper", null, 1, 7, "명령");
			require(command.startsWith("처리했습니다.") && copper(character) == 41, "Command path missed the offline character.");
			contend(ledger, character);
			require(copper(character) == 42, "Locked grant did not apply after the account lock.");
			System.out.println("Operator ledger verified.");
		} finally { cleanup(); }
	}

	private static void contend(OperatorLedger ledger, UUID character) throws Exception {
		var locked = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var failure = new AtomicReference<Exception>();
		Thread holder = new Thread(() -> {
			try (var connection = connection()) {
				connection.setAutoCommit(false);
				try (var query = connection.prepareStatement("SELECT account_id FROM dalmaru.accounts WHERE account_id=? FOR UPDATE")) {
					query.setObject(1, TARGET);
					try (var row = query.executeQuery()) { if (!row.next()) throw new IllegalStateException("Target account is missing."); }
				}
				locked.countDown();
				if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Lock holder was not released.");
				connection.commit();
			} catch (Exception error) { failure.set(error); locked.countDown(); release.countDown(); }
		});
		holder.start();
		if (!locked.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Account lock was not taken.");
		Thread releaser = new Thread(() -> { try { Thread.sleep(120); release.countDown(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
		releaser.start();
		long started = System.nanoTime();
		var granted = ledger.adjust(OPERATOR, character, "copper", null, 1, 8, "경합");
		long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		holder.join();
		if (failure.get() != null) throw failure.get();
		require(granted.appliedBalance() == 42 && waited >= 80, "Grant did not wait for the target account lock.");
	}

	private static void reject(SqlCall call) {
		try { call.run(); throw new IllegalStateException("Invalid adjustment was accepted."); }
		catch (IllegalArgumentException expected) { }
		catch (Exception error) { throw new IllegalStateException(error); }
	}

	private static int copper(UUID character) throws Exception { return scalar("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", character, null); }
	private static int quantity(UUID character, String item) throws Exception { return scalar("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?", character, item); }
	private static boolean currency(UUID character, UUID event, int delta, String reason) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT source_run_id,reason,copper_delta FROM dalmaru.character_currency_ledger WHERE event_id=? AND character_id=?")) {
			statement.setObject(1, event); statement.setObject(2, character);
			try (var row = statement.executeQuery()) { return row.next() && row.getObject(1) == null && reason.equals(row.getString(2)) && row.getInt(3) == delta; }
		}
	}
	private static boolean audit(UUID operator, long request, UUID target, UUID character) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT target_account_id,character_id FROM dalmaru.operator_adjustments WHERE operator_account_id=? AND request_no=?")) {
			statement.setObject(1, operator); statement.setLong(2, request);
			try (var row = statement.executeQuery()) { return row.next() && target.equals(row.getObject(1, UUID.class)) && character.equals(row.getObject(2, UUID.class)) && !operator.equals(target); }
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
		for (UUID account : new UUID[] { OPERATOR, TARGET }) {
			try (var connection = connection()) {
				for (String sql : new String[] { "UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?", "DELETE FROM dalmaru.operator_adjustments WHERE operator_account_id=? OR target_account_id=?", "DELETE FROM dalmaru.character_currency_ledger WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.characters WHERE account_id=?", "DELETE FROM dalmaru.accounts WHERE account_id=?" }) {
					try (var statement = connection.prepareStatement(sql)) {
						statement.setObject(1, account);
						if (sql.contains("OR target_account_id")) statement.setObject(2, account);
						statement.executeUpdate();
					} catch (java.sql.SQLException error) { if (!"42P01".equals(error.getSQLState()) && !"42703".equals(error.getSQLState())) throw error; }
				}
			}
		}
	}

	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return java.sql.DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/oscar_p2_fixture", System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	@FunctionalInterface private interface SqlCall { void run() throws Exception; }
}
