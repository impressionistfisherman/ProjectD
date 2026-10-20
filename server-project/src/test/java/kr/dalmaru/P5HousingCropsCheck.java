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
import kr.dalmaru.housing.CropRepository;
import kr.dalmaru.housing.HousingRuntime;
import kr.dalmaru.request.RequestEnvelope;

public final class P5HousingCropsCheck {
	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		FixtureMigrations.apply();
		UUID owner = UUID.randomUUID();
		UUID visit = UUID.randomUUID();
		UUID collab = UUID.randomUUID();
		try {
			var characters = new CharacterRepository();
			var accounts = new AccountRepository();
			long generation = accounts.openSession(owner).connectionGeneration();
			UUID first = characters.create(owner, generation, 1, 1, "ca" + owner.toString().substring(0, 8)).character().characterId();
			UUID second = characters.create(owner, generation, 2, 2, "cb" + owner.toString().substring(0, 8)).character().characterId();
			characters.selectActive(owner, generation, 3, first);
			var created = new HousingRuntime().createJournaled(owner, generation, 4, first, body(generation, first, "create_home", new JsonObject()));
			UUID housing = UUID.fromString(created.result().get("housingId").getAsString());
			var crops = new CropRepository();
			var planted = crops.plantJournaled(owner, generation, 5, first, plant(generation, first, housing, 0, "grain", UUID.randomUUID()));
			check("OK".equals(status(planted)) && planted.result().get("generation").getAsInt() == 1 && hours(housing, 0) == 8 && cropsOf(housing) == 1, "Plant did not schedule eight hours.");
			var early = crops.harvestJournaled(owner, generation, 6, first, harvest(generation, first, housing, 0, 1, UUID.randomUUID()));
			check("NOT_READY".equals(status(early)) && cropsOf(housing) == 1, "An early harvest removed the crop.");
			var watered = crops.tendJournaled(owner, generation, 7, first, tend(generation, first, housing, 0, "water", UUID.randomUUID()));
			check("OK".equals(status(watered)) && hours(housing, 0) == 4 && "grain".equals(crop(housing, 0)), "Water did not stop at four hours.");
			var fed = crops.tendJournaled(owner, generation, 8, first, tend(generation, first, housing, 0, "fertilize", UUID.randomUUID()));
			check("NOT_READY".equals(status(fed)) && hours(housing, 0) == 4, "Fertilizer pulled the crop below four hours.");
			shift(housing, 0, "5 hours");
			var clamped = crops.tendJournaled(owner, generation, 9, first, tend(generation, first, housing, 0, "water", UUID.randomUUID()));
			check("OK".equals(status(clamped)) && hours(housing, 0) == 4, "A five hour crop was not clamped to four hours.");
			long before = xp(first);
			shift(housing, 0, "-30 days");
			check(cropsOf(housing) == 1, "A long offline crop was removed.");
			UUID harvestRequest = UUID.randomUUID();
			var harvestBody = harvest(generation, first, housing, 0, 1, harvestRequest);
			var done = crops.harvestJournaled(owner, generation, 10, first, harvestBody);
			check("OK".equals(status(done)) && done.result().get("generation").getAsInt() == 2 && "material.grain".equals(done.result().get("materialId").getAsString()) && done.result().get("quantity").getAsInt() == 1 && done.result().get("lifeXp").getAsInt() == before + 1 && cropsOf(housing) == 0, "Harvest did not pay once.");
			var replay = crops.harvestJournaled(owner, generation, 10, first, harvestBody);
			check(replay.replayed() && xp(first) == before + 1 && quantity(first, "material.grain") == 1, "Harvest replay paid again.");
			check(cooldowns(owner) == 0, "Crop growth wrote a gathering cooldown.");
			var again = crops.plantJournaled(owner, generation, 11, first, plant(generation, first, housing, 1, "herb", UUID.randomUUID()));
			check("OK".equals(status(again)) && again.result().get("generation").getAsInt() == 1, "The second plot did not start at generation one.");
			shift(housing, 1, "-1 hour");
			var raced = concurrent(owner, generation, first, housing, 1, 1);
			check(raced && xp(first) == before + 2 && quantity(first, "material.herb") == 1, "Two characters of one account harvested one generation.");
			characters.selectActive(owner, generation, 12, second);
			var other = crops.harvestJournaled(owner, generation, 13, second, harvest(generation, second, housing, 1, 1, UUID.randomUUID()));
			check("DUPLICATE".equals(status(other)) && xp(second) == 0, "The other character harvested the same generation.");
			long visitGeneration = accounts.openSession(visit).connectionGeneration();
			UUID visitor = characters.create(visit, visitGeneration, 1, 1, "cc" + visit.toString().substring(0, 8)).character().characterId();
			characters.selectActive(visit, visitGeneration, 2, visitor);
			var refused = crops.plantJournaled(visit, visitGeneration, 3, visitor, plant(visitGeneration, visitor, housing, 2, "flower", UUID.randomUUID()));
			check("FORBIDDEN".equals(status(refused)), "A visit planted a plot.");
			long collabGeneration = accounts.openSession(collab).connectionGeneration();
			UUID partner = characters.create(collab, collabGeneration, 1, 1, "cd" + collab.toString().substring(0, 8)).character().characterId();
			characters.selectActive(collab, collabGeneration, 2, partner);
			var grantBody = new JsonObject();
			grantBody.addProperty("housingId", housing.toString());
			grantBody.addProperty("accountId", collab.toString());
			grantBody.addProperty("permission", "COLLAB");
			grantBody.addProperty("targetId", "plot:2");
			characters.selectActive(owner, generation, 14, first);
			var grant = new HousingRuntime().grantJournaled(owner, generation, 15, first, new RequestEnvelope(UUID.randomUUID(), generation, first, "grant_home", grantBody));
			check("OK".equals(grant.result().get("status").getAsString()), "Plot collaboration was not granted.");
			characters.selectActive(collab, collabGeneration, 4, partner);
			var ownPlot = crops.plantJournaled(collab, collabGeneration, 5, partner, plant(collabGeneration, partner, housing, 2, "fiber", UUID.randomUUID()));
			var otherPlot = crops.plantJournaled(collab, collabGeneration, 6, partner, plant(collabGeneration, partner, housing, 3, "vegetable", UUID.randomUUID()));
			check("OK".equals(status(ownPlot)) && "FORBIDDEN".equals(status(otherPlot)), "Collaboration was not limited to the named plot.");
			System.out.println("PASS crop hours, offline growth, one harvest, permissions, and xp replay");
		} finally {
			cleanup(owner);
			cleanup(visit);
			cleanup(collab);
		}
	}

	private static boolean concurrent(UUID account, long generation, UUID character, UUID housing, int plot, int cropGeneration) throws Exception {
		var one = harvest(generation, character, housing, plot, cropGeneration, UUID.randomUUID());
		var two = harvest(generation, character, housing, plot, cropGeneration, UUID.randomUUID());
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			var left = pool.submit(() -> { start.await(); return new CropRepository().harvestJournaled(account, generation, 20, character, one); });
			var right = pool.submit(() -> { start.await(); return new CropRepository().harvestJournaled(account, generation, 21, character, two); });
			start.countDown();
			String sa = status(left.get(10, TimeUnit.SECONDS));
			String sb = status(right.get(10, TimeUnit.SECONDS));
			return (sa.equals("OK") && sb.equals("DUPLICATE")) || (sa.equals("DUPLICATE") && sb.equals("OK"));
		} finally { pool.shutdownNow(); }
	}

	private static String status(CropRepository.Journaled result) { return result.result().get("status").getAsString(); }

	private static RequestEnvelope body(long generation, UUID character, String operation, JsonObject payload) {
		return new RequestEnvelope(UUID.randomUUID(), generation, character, operation, payload);
	}

	private static RequestEnvelope plant(long generation, UUID character, UUID housing, int plot, String crop, UUID request) {
		var payload = new JsonObject();
		payload.addProperty("housingId", housing.toString());
		payload.addProperty("plot", plot);
		payload.addProperty("crop", crop);
		return new RequestEnvelope(request, generation, character, "plant_crop", payload);
	}

	private static RequestEnvelope tend(long generation, UUID character, UUID housing, int plot, String action, UUID request) {
		var payload = new JsonObject();
		payload.addProperty("housingId", housing.toString());
		payload.addProperty("plot", plot);
		payload.addProperty("action", action);
		return new RequestEnvelope(request, generation, character, "tend_crop", payload);
	}

	private static RequestEnvelope harvest(long generation, UUID character, UUID housing, int plot, int cropGeneration, UUID request) {
		var payload = new JsonObject();
		payload.addProperty("housingId", housing.toString());
		payload.addProperty("plot", plot);
		payload.addProperty("generation", cropGeneration);
		return new RequestEnvelope(request, generation, character, "harvest_crop", payload);
	}

	private static int hours(UUID housing, int plot) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT EXTRACT(EPOCH FROM ready_at - planted_at) FROM dalmaru.home_crops WHERE housing_id=? AND plot=?")) {
			query.setObject(1, housing);
			query.setInt(2, plot);
			try (var row = query.executeQuery()) { row.next(); return (int) (row.getDouble(1) / 3600); }
		}
	}

	private static String crop(UUID housing, int plot) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT crop FROM dalmaru.home_crops WHERE housing_id=? AND plot=?")) {
			query.setObject(1, housing);
			query.setInt(2, plot);
			try (var row = query.executeQuery()) { row.next(); return row.getString(1); }
		}
	}

	private static void shift(UUID housing, int plot, String interval) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("UPDATE dalmaru.home_crops SET ready_at=planted_at + ?::interval WHERE housing_id=? AND plot=?")) {
			update.setString(1, interval);
			update.setObject(2, housing);
			update.setInt(3, plot);
			if (update.executeUpdate() != 1) throw new SQLException("Crop clock was not moved.");
		}
	}

	private static int cropsOf(UUID housing) throws SQLException { return count("SELECT count(*) FROM dalmaru.home_crops WHERE housing_id=?", housing); }
	private static int cooldowns(UUID account) throws SQLException { return count("SELECT count(*) FROM dalmaru.account_gather_cooldowns WHERE account_id=?", account); }
	private static long xp(UUID character) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT xp FROM dalmaru.character_life_xp WHERE character_id=?")) {
			query.setObject(1, character);
			try (var row = query.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
		}
	}

	private static int quantity(UUID character, String material) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			query.setObject(1, character);
			query.setString(2, material);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private static int count(String sql, UUID id) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement(sql)) {
			query.setObject(1, id);
			try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private static java.sql.Connection connection() throws SQLException {
		return DriverManager.getConnection("jdbc:postgresql://localhost:15432/" + FixtureDatabase.databaseName(), "postgres", System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup(UUID account) throws SQLException {
		try (var connection = connection()) {
			connection.setAutoCommit(false);
			try {
				for (String sql : java.util.List.of(
					"DELETE FROM dalmaru.account_home_presence WHERE housing_id IN (SELECT housing_id FROM dalmaru.account_homes WHERE account_id=?)",
					"DELETE FROM dalmaru.account_home_presence WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.account_homes WHERE account_id=?",
					"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
					"DELETE FROM dalmaru.character_life_xp WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
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
