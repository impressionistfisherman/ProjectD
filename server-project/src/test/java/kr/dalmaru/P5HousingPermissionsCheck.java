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
import kr.dalmaru.housing.HousingRuntime;
import kr.dalmaru.request.RequestEnvelope;

public final class P5HousingPermissionsCheck {
	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		FixtureMigrations.apply();
		UUID owner = UUID.randomUUID();
		UUID offline = UUID.randomUUID();
		UUID visit = UUID.randomUUID();
		UUID invite = UUID.randomUUID();
		UUID collab = UUID.randomUUID();
		UUID stranger = UUID.randomUUID();
		try {
			var characters = new CharacterRepository();
			var accounts = new AccountRepository();
			long generation = accounts.openSession(owner).connectionGeneration();
			UUID character = characters.create(owner, generation, 1, 1, "pa" + owner.toString().substring(0, 8)).character().characterId();
			characters.selectActive(owner, generation, 2, character);
			accounts.openSession(offline);
			var created = new HousingRuntime().createJournaled(owner, generation, 3, character, envelope(generation, character, "create_home", new JsonObject()));
			check("OK".equals(created.result().get("status").getAsString()), "Home was not created.");
			UUID housing = UUID.fromString(created.result().get("housingId").getAsString());
			int strength = strength(character);
			var granted = concurrentGrant(owner, generation, character, housing, offline);
			check(granted != null, "Concurrent grant did not keep one visit.");
			var replayed = new HousingRuntime().grantJournaled(owner, generation, 10, character, grantEnvelope(generation, character, housing, offline, "VISIT", null, granted.request()));
			check(replayed.replayed() && "OK".equals(replayed.result().get("status").getAsString()), "Grant replay changed the row.");
			var revoked = concurrentRevoke(owner, generation, character, granted.grantId());
			check(revoked, "Concurrent revoke did not settle once.");
			check("FORBIDDEN".equals(new HousingRuntime().permit(offline, housing, "ENTER", null)), "A revoked visit still entered.");
			long visitGeneration = accounts.openSession(visit).connectionGeneration();
			UUID visitCharacter = characters.create(visit, visitGeneration, 1, 1, "pb" + visit.toString().substring(0, 8)).character().characterId();
			characters.selectActive(visit, visitGeneration, 2, visitCharacter);
			grant(owner, generation, 20, character, housing, visit, "VISIT", null);
			long inviteGeneration = accounts.openSession(invite).connectionGeneration();
			characters.create(invite, inviteGeneration, 1, 1, "pc" + invite.toString().substring(0, 8));
			grant(owner, generation, 21, character, housing, invite, "INVITE", null);
			long collabGeneration = accounts.openSession(collab).connectionGeneration();
			UUID collabCharacter = characters.create(collab, collabGeneration, 1, 1, "pd" + collab.toString().substring(0, 8)).character().characterId();
			grant(owner, generation, 22, character, housing, collab, "COLLAB", "plot:0");
			long strangerGeneration = accounts.openSession(stranger).connectionGeneration();
			UUID strangerCharacter = characters.create(stranger, strangerGeneration, 1, 1, "pe" + stranger.toString().substring(0, 8)).character().characterId();
			check(table(owner, housing, null) && table(visit, housing, null) && table(invite, housing, null) && table(collab, housing, "plot:0") && table(stranger, housing, null), "The permission table drifted.");
			var guest = new HousingRuntime().enter(visit, visitCharacter, housing, "minecraft:overworld", 3, 64, 4);
			check("OK".equals(guest.status()) && guest.inside(), "A visit could not enter.");
			var denied = new HousingRuntime().enter(stranger, strangerCharacter, housing, "minecraft:overworld", 5, 64, 6);
			check("FORBIDDEN".equals(denied.status()), "An account without a grant entered.");
			var notOwner = new HousingRuntime().grantJournaled(visit, visitGeneration, 2, visitCharacter, grantEnvelope(visitGeneration, visitCharacter, housing, stranger, "VISIT", null, UUID.randomUUID()));
			check("NOT_OWNER".equals(notOwner.result().get("status").getAsString()), "A visitor granted a permission.");
			var dry = new HousingRuntime().placeJournaled(owner, generation, 30, character, changeEnvelope(generation, character, "place_home", housing, "station:loom", UUID.randomUUID()));
			check("INSUFFICIENT_MATERIAL".equals(dry.result().get("status").getAsString()) && changes(housing) == 0, "A place without timber was stored.");
			timber(character, 1);
			UUID expandRequest = UUID.randomUUID();
			var expandBody = changeEnvelope(generation, character, "expand_home", housing, "plot:0", expandRequest);
			var expanded = new HousingRuntime().expandJournaled(owner, generation, 31, character, expandBody);
			check("OK".equals(expanded.result().get("status").getAsString()) && expanded.result().get("size").getAsInt() == 12 && timberQuantity(character) == 0 && strength(character) == strength, "Expansion changed combat stats or the plot size.");
			var same = new HousingRuntime().expandJournaled(owner, generation, 31, character, expandBody);
			check(same.replayed() && timberQuantity(character) == 0 && changes(housing) == 1, "Expansion replay spent timber again.");
			check("FORBIDDEN".equals(new HousingRuntime().permit(owner, housing, "DEMOLISH", null)) && spaces(housing) == 6, "The home was demolished.");
			check("FORBIDDEN".equals(new HousingRuntime().permit(collab, housing, "STORAGE", null)) && "FORBIDDEN".equals(new HousingRuntime().permit(collab, housing, "MATERIAL", null)) && "FORBIDDEN".equals(new HousingRuntime().permit(collab, housing, "STALL", null)), "Another account opened owner storage.");
			var back = new HousingRuntime().leave(visit, visitCharacter);
			check("OK".equals(back.status()) && !back.inside() && back.x() == 3, "A visitor could not return.");
			System.out.println("PASS home permissions, offline grant, revoke, storage refusal, and expansion replay");
		} finally {
			cleanup(owner);
			cleanup(offline);
			cleanup(visit);
			cleanup(invite);
			cleanup(collab);
			cleanup(stranger);
		}
	}

	private static boolean table(UUID account, UUID housing, String plot) throws SQLException {
		var homes = new HousingRuntime();
		boolean owner = plot == null && "OK".equals(homes.permit(account, housing, "STORAGE", null));
		if (owner) return "OK".equals(homes.permit(account, housing, "ENTER", null)) && "OK".equals(homes.permit(account, housing, "SHOP", null)) && "OK".equals(homes.permit(account, housing, "FEATURE", null)) && "OK".equals(homes.permit(account, housing, "PLOT", "plot:0")) && "OK".equals(homes.permit(account, housing, "STATION", "station:loom")) && "FORBIDDEN".equals(homes.permit(account, housing, "DEMOLISH", null));
		String enter = homes.permit(account, housing, "ENTER", null);
		String shop = homes.permit(account, housing, "SHOP", null);
		String feature = homes.permit(account, housing, "FEATURE", null);
		String named = homes.permit(account, housing, "PLOT", "plot:0");
		String other = homes.permit(account, housing, "PLOT", "plot:1");
		String station = homes.permit(account, housing, "STATION", "station:loom");
		String storage = homes.permit(account, housing, "STORAGE", null);
		if ("plot:0".equals(plot)) return "OK".equals(enter) && "FORBIDDEN".equals(shop) && "FORBIDDEN".equals(feature) && "OK".equals(named) && "FORBIDDEN".equals(other) && "FORBIDDEN".equals(station) && "FORBIDDEN".equals(storage) && "NOT_OWNER".equals(homes.permit(account, housing, "EXPAND", "plot:0"));
		if ("OK".equals(feature)) return "OK".equals(enter) && "OK".equals(shop) && "FORBIDDEN".equals(named) && "FORBIDDEN".equals(storage);
		if ("OK".equals(shop)) return "OK".equals(enter) && "FORBIDDEN".equals(feature) && "FORBIDDEN".equals(named) && "FORBIDDEN".equals(storage);
		return "FORBIDDEN".equals(enter) && "FORBIDDEN".equals(shop) && "FORBIDDEN".equals(feature) && "FORBIDDEN".equals(named) && "FORBIDDEN".equals(storage);
	}

	private record Won(UUID grantId, UUID request) { }

	private static Won concurrentGrant(UUID owner, long generation, UUID character, UUID housing, UUID target) throws Exception {
		var one = grantEnvelope(generation, character, housing, target, "VISIT", null, UUID.randomUUID());
		var two = grantEnvelope(generation, character, housing, target, "VISIT", null, UUID.randomUUID());
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			var left = pool.submit(() -> { start.await(); return new HousingRuntime().grantJournaled(owner, generation, 10, character, one); });
			var right = pool.submit(() -> { start.await(); return new HousingRuntime().grantJournaled(owner, generation, 11, character, two); });
			start.countDown();
			var a = left.get(10, TimeUnit.SECONDS);
			var b = right.get(10, TimeUnit.SECONDS);
			String sa = a.result().get("status").getAsString();
			String sb = b.result().get("status").getAsString();
			check((sa.equals("OK") && sb.equals("DUPLICATE")) || (sa.equals("DUPLICATE") && sb.equals("OK")), "Concurrent grant did not keep one row.");
			var winner = sa.equals("OK") ? a : b;
			return new Won(UUID.fromString(winner.result().get("grantId").getAsString()), winner.receipt().requestId());
		} finally { pool.shutdownNow(); }
	}

	private static boolean concurrentRevoke(UUID owner, long generation, UUID character, UUID grantId) throws Exception {
		var one = new JsonObject();
		one.addProperty("grantId", grantId.toString());
		var first = new RequestEnvelope(UUID.randomUUID(), generation, character, "revoke_home", one);
		var second = new RequestEnvelope(UUID.randomUUID(), generation, character, "revoke_home", one.deepCopy());
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			var left = pool.submit(() -> { start.await(); return new HousingRuntime().revokeJournaled(owner, generation, 12, character, first); });
			var right = pool.submit(() -> { start.await(); return new HousingRuntime().revokeJournaled(owner, generation, 13, character, second); });
			start.countDown();
			var a = left.get(10, TimeUnit.SECONDS);
			var b = right.get(10, TimeUnit.SECONDS);
			String sa = a.result().get("status").getAsString();
			String sb = b.result().get("status").getAsString();
			return (sa.equals("OK") && sb.equals("CONFLICT")) || (sa.equals("CONFLICT") && sb.equals("OK"));
		} finally { pool.shutdownNow(); }
	}

	private static void grant(UUID owner, long generation, long correlation, UUID character, UUID housing, UUID target, String permission, String targetId) throws SQLException {
		var result = new HousingRuntime().grantJournaled(owner, generation, correlation, character, grantEnvelope(generation, character, housing, target, permission, targetId, UUID.randomUUID()));
		check("OK".equals(result.result().get("status").getAsString()), "Grant " + permission + " failed.");
	}

	private static RequestEnvelope envelope(long generation, UUID character, String operation, JsonObject body) {
		return new RequestEnvelope(UUID.randomUUID(), generation, character, operation, body);
	}

	private static RequestEnvelope grantEnvelope(long generation, UUID character, UUID housing, UUID target, String permission, String targetId, UUID request) {
		var body = new JsonObject();
		body.addProperty("housingId", housing.toString());
		body.addProperty("accountId", target.toString());
		body.addProperty("permission", permission);
		if (targetId != null) body.addProperty("targetId", targetId);
		return new RequestEnvelope(request, generation, character, "grant_home", body);
	}

	private static RequestEnvelope changeEnvelope(long generation, UUID character, String operation, UUID housing, String changeId, UUID request) {
		var body = new JsonObject();
		body.addProperty("housingId", housing.toString());
		body.addProperty("changeId", changeId);
		return new RequestEnvelope(request, generation, character, operation, body);
	}

	private static int strength(UUID character) throws SQLException { return count("SELECT strength FROM dalmaru.character_profiles WHERE character_id=?", character); }
	private static int timberQuantity(UUID character) throws SQLException { return count("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.timber'", character); }
	private static int changes(UUID housing) throws SQLException { return count("SELECT count(*) FROM dalmaru.account_home_changes WHERE housing_id=?", housing); }
	private static int spaces(UUID housing) throws SQLException { return count("SELECT count(*) FROM dalmaru.account_home_spaces WHERE housing_id=?", housing); }

	private static void timber(UUID character, int quantity) throws SQLException {
		try (var connection = connection(); var insert = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.timber', ?, 'FREE')")) {
			insert.setObject(1, character);
			insert.setInt(2, quantity);
			insert.executeUpdate();
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
