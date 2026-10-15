package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.run.RunRepository;

public final class RunWipeCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000011a");
	private static final UUID PAUSED = UUID.fromString("00000000-0000-4000-8000-0000000001a1");
	private static final UUID DONE = UUID.fromString("00000000-0000-4000-8000-0000000001a2");
	private RunWipeCheck() { }

	public static void main(String[] args) throws Exception {
		if (!"oscar_p1_fixture".equals(System.getenv("DALMARU_DB_NAME"))) throw new IllegalStateException("Refusing to run outside oscar_p1_fixture.");
		String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
		String absent = runtime.substring(runtime.indexOf("private DeathResult recordAbsentDeath"), runtime.indexOf("public long pausedTicks"));
		String online = runtime.substring(runtime.indexOf("public DeathResult recordDeath"), runtime.indexOf("public boolean canRespawn"));
		if (!absent.contains("failUnattendedWipe") || online.contains("failUnattendedWipe")) throw new IllegalStateException("Wipe marking is on the wrong death path.");
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var runs = new RunRepository();
		cleanup();
		try {
			accounts.openSession(ACCOUNT);
			UUID character = characters.create(ACCOUNT, 1, "ops_m11a").characterId();
			insert(character);
			if (!runs.failUnattendedWipe(PAUSED) || runs.failUnattendedWipe(PAUSED) || runs.failUnattendedWipe(DONE)) throw new IllegalStateException("Wipe update did not stop at the paused run.");
			if (!"FAILED".equals(state(PAUSED)) || !"COMPLETED".equals(state(DONE)) || clears(character) != 1 || runs.active(character) != null) throw new IllegalStateException("Wiped run is still resumable or consumed a weekly clear.");
			System.out.println("Run wipe verified.");
		} finally { cleanup(); }
	}

	private static void insert(UUID character) throws Exception {
		try (var connection = connection()) {
			try (var run = connection.prepareStatement("INSERT INTO dalmaru.run_instances(run_id,content_id,difficulty,content_version,week_id,party_size,scaling_tier,state) VALUES(?,'run.bongsudae','FIRST_STEP','pilot-1',CURRENT_DATE,1,1,?)")) {
				run.setObject(1, PAUSED); run.setString(2, "PAUSED"); run.executeUpdate();
				run.setObject(1, DONE); run.setString(2, "COMPLETED"); run.executeUpdate();
			}
			try (var member = connection.prepareStatement("INSERT INTO dalmaru.run_participants(run_id,character_id) VALUES(?,?)")) { member.setObject(1, PAUSED); member.setObject(2, character); member.executeUpdate(); }
			try (var week = connection.prepareStatement("INSERT INTO dalmaru.character_run_weekly_clears(character_id,content_id,difficulty,week_id,clear_count) VALUES(?,'run.bongsudae','FIRST_STEP',CURRENT_DATE,1)")) { week.setObject(1, character); week.executeUpdate(); }
		}
	}

	private static String state(UUID run) throws Exception {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT state FROM dalmaru.run_instances WHERE run_id=?")) { query.setObject(1, run); try (var row = query.executeQuery()) { if (!row.next()) throw new IllegalStateException("Run row is missing."); return row.getString(1); } }
	}

	private static int clears(UUID character) throws Exception {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT clear_count FROM dalmaru.character_run_weekly_clears WHERE character_id=?")) { query.setObject(1, character); try (var row = query.executeQuery()) { if (!row.next()) throw new IllegalStateException("Weekly row is missing."); return row.getInt(1); } }
	}

	private static void cleanup() throws Exception {
		if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return;
		try (var connection = connection()) {
			for (String sql : new String[] { "UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?", "DELETE FROM dalmaru.character_run_weekly_clears WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.run_participants WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.run_instances WHERE run_id IN (?, ?)", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.characters WHERE account_id=?", "DELETE FROM dalmaru.accounts WHERE account_id=?" }) {
				try (var statement = connection.prepareStatement(sql)) {
					if (sql.contains("run_id IN")) { statement.setObject(1, PAUSED); statement.setObject(2, DONE); }
					else statement.setObject(1, ACCOUNT);
					statement.executeUpdate();
				}
			}
		}
	}

	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return java.sql.DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/oscar_p1_fixture", System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}
}
