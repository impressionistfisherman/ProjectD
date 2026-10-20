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
import kr.dalmaru.craft.ProductionQueueRepository;
import kr.dalmaru.housing.HousingRuntime;
import kr.dalmaru.request.RequestEnvelope;

public final class P5CraftProductionQueueCheck {
	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		FixtureMigrations.apply();
		UUID owner = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		try {
			var characters = new CharacterRepository();
			var accounts = new AccountRepository();
			long generation = accounts.openSession(owner).connectionGeneration();
			UUID character = characters.create(owner, generation, 1, 1, "qa" + owner.toString().substring(0, 8)).character().characterId();
			characters.selectActive(owner, generation, 2, character);
			var created = new HousingRuntime().createJournaled(owner, generation, 3, character, new RequestEnvelope(UUID.randomUUID(), generation, character, "create_home", new JsonObject()));
			UUID housing = UUID.fromString(created.result().get("housingId").getAsString());
			var queue = new ProductionQueueRepository();
			stock(character, "material.ironstone", 1);
			var shortFall = queue.enqueueJournaled(owner, generation, 4, character, enqueue(generation, character, housing, "craft.iron_ingot", 2, UUID.randomUUID()));
			check("INSUFFICIENT_MATERIAL".equals(status(shortFall)) && quantity(character, "material.ironstone") == 1 && jobs(housing) == 0, "A short queue locked material.");
			stock(character, "material.ironstone", 2);
			var forbidden = queue.enqueueJournaled(owner, generation, 5, character, enqueue(generation, character, housing, "craft.precision_blade", 1, UUID.randomUUID()));
			var task = queue.enqueueJournaled(owner, generation, 6, character, enqueue(generation, character, housing, "task.blacksmith", 1, UUID.randomUUID()));
			check("FORBIDDEN".equals(status(forbidden)) && "FORBIDDEN".equals(status(task)) && quantity(character, "material.ironstone") == 2, "A precision order or promotion task was queued.");
			boolean rejected = false;
			try { queue.enqueueJournaled(owner, generation, 7, character, repeat(generation, character, housing)); }
			catch (IllegalArgumentException exception) { rejected = true; }
			check(rejected, "A repeat field was accepted.");
			var queued = queue.enqueueJournaled(owner, generation, 8, character, enqueue(generation, character, housing, "craft.iron_ingot", 2, UUID.randomUUID()));
			UUID job = UUID.fromString(queued.result().get("jobId").getAsString());
			String ready = readyAt(job);
			check("OK".equals(status(queued)) && quantity(character, "material.ironstone") == 0 && locked(job) == 2 && ready.equals(readyAt(job)), "Queued material was not locked.");
			var cancelled = queue.cancelJournaled(owner, generation, 9, character, job(generation, character, "cancel_production", job, UUID.randomUUID()));
			check("OK".equals(status(cancelled)) && quantity(character, "material.ironstone") == 2, "Cancel did not return the material.");
			var again = queue.enqueueJournaled(owner, generation, 10, character, enqueue(generation, character, housing, "craft.iron_ingot", 2, UUID.randomUUID()));
			UUID second = UUID.fromString(again.result().get("jobId").getAsString());
			shift(second);
			var raced = race(owner, generation, character, second);
			check(raced && quantity(character, "material.iron_ingot") == 2 && quantity(character, "material.ironstone") == 0, "Claim and cancel both changed a finished job.");
			stock(character, "material.ironstone", 2);
			var third = queue.enqueueJournaled(owner, generation, 11, character, enqueue(generation, character, housing, "craft.iron_ingot", 1, UUID.randomUUID()));
			UUID thirdId = UUID.fromString(third.result().get("jobId").getAsString());
			shift(thirdId);
			UUID claimRequest = UUID.randomUUID();
			var claimBody = job(generation, character, "claim_production", thirdId, claimRequest);
			var claimed = new ProductionQueueRepository().claimJournaled(owner, generation, 12, character, claimBody);
			var replay = new ProductionQueueRepository().claimJournaled(owner, generation, 12, character, claimBody);
			check("OK".equals(status(claimed)) && claimed.result().get("quantity").getAsInt() == 3 && replay.replayed() && quantity(character, "material.iron_ingot") == 3, "A restarted claim paid twice.");
			var duplicate = concurrentClaim(owner, generation, character, housing);
			check(duplicate, "Two claims paid one job twice.");
			long otherGeneration = accounts.openSession(other).connectionGeneration();
			UUID stranger = characters.create(other, otherGeneration, 1, 1, "qb" + other.toString().substring(0, 8)).character().characterId();
			characters.selectActive(other, otherGeneration, 2, stranger);
			stock(stranger, "material.ironstone", 1);
			var refused = queue.enqueueJournaled(other, otherGeneration, 3, stranger, enqueue(otherGeneration, stranger, housing, "craft.iron_ingot", 1, UUID.randomUUID()));
			check("FORBIDDEN".equals(status(refused)) && quantity(stranger, "material.ironstone") == 1 && cooldowns(owner) == 0, "Another account queued at the workshop.");
			System.out.println("PASS production lock, forbidden recipes, cancel, claim race, and restart");
		} finally { cleanup(owner); cleanup(other); }
	}

	private static boolean race(UUID account, long generation, UUID character, UUID job) throws Exception {
		var claim = job(generation, character, "claim_production", job, UUID.randomUUID());
		var cancel = job(generation, character, "cancel_production", job, UUID.randomUUID());
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			var left = pool.submit(() -> { start.await(); return new ProductionQueueRepository().claimJournaled(account, generation, 20, character, claim); });
			var right = pool.submit(() -> { start.await(); return new ProductionQueueRepository().cancelJournaled(account, generation, 21, character, cancel); });
			start.countDown();
			String sa = status(left.get(10, TimeUnit.SECONDS));
			String sb = status(right.get(10, TimeUnit.SECONDS));
			return (sa.equals("OK") && sb.equals("CONFLICT")) || (sa.equals("CONFLICT") && sb.equals("OK"));
		} finally { pool.shutdownNow(); }
	}

	private static boolean concurrentClaim(UUID account, long generation, UUID character, UUID housing) throws Exception {
		stock(character, "material.ironstone", 1);
		var queued = new ProductionQueueRepository().enqueueJournaled(account, generation, 30, character, enqueue(generation, character, housing, "craft.iron_ingot", 1, UUID.randomUUID()));
		UUID job = UUID.fromString(queued.result().get("jobId").getAsString());
		shift(job);
		int before = quantity(character, "material.iron_ingot");
		var one = job(generation, character, "claim_production", job, UUID.randomUUID());
		var two = job(generation, character, "claim_production", job, UUID.randomUUID());
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			var left = pool.submit(() -> { start.await(); return new ProductionQueueRepository().claimJournaled(account, generation, 31, character, one); });
			var right = pool.submit(() -> { start.await(); return new ProductionQueueRepository().claimJournaled(account, generation, 32, character, two); });
			start.countDown();
			String sa = status(left.get(10, TimeUnit.SECONDS));
			String sb = status(right.get(10, TimeUnit.SECONDS));
			return ((sa.equals("OK") && sb.equals("DUPLICATE")) || (sa.equals("DUPLICATE") && sb.equals("OK"))) && quantity(character, "material.iron_ingot") == before + 1;
		} finally { pool.shutdownNow(); }
	}

	private static String status(ProductionQueueRepository.Journaled result) { return result.result().get("status").getAsString(); }

	private static RequestEnvelope enqueue(long generation, UUID character, UUID housing, String recipe, int quantity, UUID request) {
		var body = new JsonObject();
		body.addProperty("housingId", housing.toString());
		body.addProperty("recipeId", recipe);
		body.addProperty("quantity", quantity);
		return new RequestEnvelope(request, generation, character, "enqueue_production", body);
	}

	private static RequestEnvelope repeat(long generation, UUID character, UUID housing) {
		var body = new JsonObject();
		body.addProperty("housingId", housing.toString());
		body.addProperty("recipeId", "craft.iron_ingot");
		body.addProperty("quantity", 1);
		body.addProperty("repeat", true);
		return new RequestEnvelope(UUID.randomUUID(), generation, character, "enqueue_production", body);
	}

	private static RequestEnvelope job(long generation, UUID character, String operation, UUID job, UUID request) {
		var body = new JsonObject();
		body.addProperty("jobId", job.toString());
		return new RequestEnvelope(request, generation, character, operation, body);
	}

	private static void stock(UUID character, String material, int quantity) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, 'FREE') ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=EXCLUDED.quantity")) {
			update.setObject(1, character);
			update.setString(2, material);
			update.setInt(3, quantity);
			update.executeUpdate();
		}
	}

	private static void shift(UUID job) throws SQLException {
		try (var connection = connection(); var update = connection.prepareStatement("UPDATE dalmaru.production_jobs SET ready_at=clock_timestamp() - interval '1 hour' WHERE job_id=?")) {
			update.setObject(1, job);
			if (update.executeUpdate() != 1) throw new SQLException("Production clock was not moved.");
		}
	}

	private static String readyAt(UUID job) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT ready_at::text FROM dalmaru.production_jobs WHERE job_id=?")) {
			query.setObject(1, job);
			try (var row = query.executeQuery()) { row.next(); return row.getString(1); }
		}
	}

	private static int locked(UUID job) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT quantity FROM dalmaru.production_materials WHERE job_id=?")) {
			query.setObject(1, job);
			try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private static int quantity(UUID character, String material) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			query.setObject(1, character);
			query.setString(2, material);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private static int jobs(UUID housing) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT count(*) FROM dalmaru.production_jobs WHERE housing_id=?")) {
			query.setObject(1, housing);
			try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private static int cooldowns(UUID account) throws SQLException {
		try (var connection = connection(); var query = connection.prepareStatement("SELECT count(*) FROM dalmaru.account_gather_cooldowns WHERE account_id=?")) {
			query.setObject(1, account);
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
					"DELETE FROM dalmaru.account_homes WHERE account_id=?",
					"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
					"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
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
