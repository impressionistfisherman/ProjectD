package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.housing.HousingRepository;
import kr.dalmaru.housing.HousingRuntime;
import kr.dalmaru.request.RequestEnvelope;

public final class P5HousingLifecycleCheck {
	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		FixtureMigrations.apply();
		UUID account = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		try {
			var characters = new CharacterRepository();
			long generation = new AccountRepository().openSession(account).connectionGeneration();
			UUID first = characters.create(account, generation, 1, 1, "ha" + account.toString().substring(0, 8)).character().characterId();
			UUID second = characters.create(account, generation, 2, 2, "hb" + account.toString().substring(0, 8)).character().characterId();
			characters.selectActive(account, generation, 3, first);
			var body = new JsonObject();
			var one = new RequestEnvelope(UUID.randomUUID(), generation, first, "create_home", body);
			var two = new RequestEnvelope(UUID.randomUUID(), generation, first, "create_home", body);
			var pool = Executors.newFixedThreadPool(2);
			var start = new CountDownLatch(1);
			try {
				var left = pool.submit(() -> { start.await(); return new HousingRuntime().createJournaled(account, generation, 4, first, one); });
				var right = pool.submit(() -> { start.await(); return new HousingRuntime().createJournaled(account, generation, 5, first, two); });
				start.countDown();
				var a = left.get(10, TimeUnit.SECONDS);
				var b = right.get(10, TimeUnit.SECONDS);
				String sa = a.result().get("status").getAsString();
				String sb = b.result().get("status").getAsString();
				check((sa.equals("OK") && sb.equals("FULL")) || (sa.equals("FULL") && sb.equals("OK")), "Concurrent create did not keep one home.");
				check(a.result().get("housingId").getAsString().equals(b.result().get("housingId").getAsString()), "The two creates did not share one housing id.");
			} finally { pool.shutdownNow(); }
			UUID housing = housingOf(account);
			check(homes(account) == 1 && spaces(housing) == 6 && plots(housing) == 4 && separated(housing) && runs(first) == 0 && runs(second) == 0, "The home mixed its spaces or a dungeon row.");
			var replay = new HousingRuntime().createJournaled(account, generation, 4, first, one);
			check(replay.replayed() && homes(account) == 1, "Create replay added a home.");
			var entered = new HousingRuntime().enter(account, first, housing, "minecraft:overworld", 10, 64, 12);
			characters.selectActive(account, generation, 6, second);
			var shared = new HousingRuntime().enter(account, second, housing, "minecraft:overworld", 14, 64, 16);
			check("OK".equals(entered.status()) && entered.inside() && entered.housingId().equals(housing) && HousingRepository.DIMENSION.equals(entered.dimension()) && entered.x() == 0 && entered.y() == 64 && entered.z() == 0, "Entry did not use the house origin.");
			check(shared.housingId().equals(housing) && shared.inside(), "The second character did not share the home.");
			var restarted = new HousingRuntime().recover(first);
			check(restarted.inside() && housing.equals(restarted.housingId()) && restarted.x() == 0 && restarted.z() == 0, "Restart did not restore the stored home.");
			var back = new HousingRuntime().leave(account, first);
			check("OK".equals(back.status()) && !back.inside() && "minecraft:overworld".equals(back.dimension()) && back.x() == 10 && back.y() == 64 && back.z() == 12, "Leave did not restore the village point.");
			var after = new HousingRuntime().recover(first);
			check(!after.inside() && after.x() == 10 && after.z() == 12, "A later recover put the character back inside.");
			long otherGeneration = new AccountRepository().openSession(other).connectionGeneration();
			UUID stranger = characters.create(other, otherGeneration, 7, 1, "hc" + other.toString().substring(0, 8)).character().characterId();
			var refused = new HousingRuntime().enter(other, stranger, housing, "minecraft:overworld", 1, 64, 1);
			check("FORBIDDEN".equals(refused.status()) && runs(stranger) == 0 && homes(other) == 0, "Another account entered the home.");
			System.out.println("PASS one home per account, shared characters, separated spaces, enter leave and restart recovery");
		} finally { cleanup(account); cleanup(other); }
	}

	private static UUID housingOf(UUID account) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT housing_id FROM dalmaru.account_homes WHERE account_id=?")) {
			query.setObject(1, account);
			try (var row = query.executeQuery()) { if (!row.next()) throw new AssertionError("Home row missing."); return row.getObject(1, UUID.class); }
		}
	}

	private static int homes(UUID account) throws SQLException { return count("SELECT count(*) FROM dalmaru.account_homes WHERE account_id=?", account); }
	private static int spaces(UUID housing) throws SQLException { return count("SELECT count(*) FROM dalmaru.account_home_spaces WHERE housing_id=?", housing); }
	private static int plots(UUID housing) throws SQLException { return count("SELECT count(*) FROM dalmaru.account_home_spaces WHERE housing_id=? AND kind='PLOT'", housing); }
	private static int runs(UUID character) throws SQLException { return count("SELECT count(*) FROM dalmaru.run_participants WHERE character_id=?", character); }

	private static int count(String sql, UUID id) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement(sql)) {
			query.setObject(1, id);
			try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private static boolean separated(UUID housing) throws SQLException {
		record Box(int x, int z, int size) { }
		var boxes = new java.util.ArrayList<Box>();
		try (var connection = connection(); var query = connection.prepareStatement("SELECT origin_x, origin_z, size, kind FROM dalmaru.account_home_spaces WHERE housing_id=?")) {
			query.setObject(1, housing);
			try (var row = query.executeQuery()) {
				while (row.next()) {
					if ("HOUSE".equals(row.getString(4)) && row.getInt(3) != 16) return false;
					boxes.add(new Box(row.getInt(1), row.getInt(2), row.getInt(3)));
				}
			}
		}
		if (boxes.size() != 6) return false;
		for (int i = 0; i < boxes.size(); i++) for (int j = i + 1; j < boxes.size(); j++) {
			var a = boxes.get(i); var b = boxes.get(j);
			boolean x = a.x() < b.x() + b.size() && b.x() < a.x() + a.size();
			boolean z = a.z() < b.z() + b.size() && b.z() < a.z() + a.size();
			if (x && z) return false;
		}
		return true;
	}

	private static java.sql.Connection connection() throws SQLException {
		return DriverManager.getConnection("jdbc:postgresql://localhost:15432/" + FixtureDatabase.databaseName(), "postgres", System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup(UUID account) throws SQLException {
		try (var connection = connection()) {
			connection.setAutoCommit(false);
			try {
				for (String sql : java.util.List.of(
					"DELETE FROM dalmaru.account_home_presence WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.account_homes WHERE account_id=?",
					"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
					"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
					"DELETE FROM dalmaru.character_selection_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_creation_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.characters WHERE account_id=?",
					"DELETE FROM dalmaru.accounts WHERE account_id=?")) {
					try (var query = connection.prepareStatement(sql)) { query.setObject(1, account); query.executeUpdate(); }
				}
				connection.commit();
			} catch (SQLException exception) { connection.rollback(); throw exception; }
		}
	}

	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
