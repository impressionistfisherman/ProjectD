package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.mail.MailboxClaim;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunRepository;

public final class MailboxClaimCheck {
	private MailboxClaimCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		FixtureMigrations.apply();
		UUID account = UUID.randomUUID();
		UUID run = null;
		try {
			var session = new AccountRepository().openSession(account);
			long generation = session.connectionGeneration();
			UUID character = new CharacterRepository().create(account, generation, 1, 1, "우편_" + account.toString().substring(0, 8)).character().characterId();
			new CharacterRepository().selectActive(account, generation, 2, character);
			sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?", character);
			var runs = new RunRepository();
			run = runs.enter(account, generation, 10, RunDifficulty.FIRST_STEP, List.of(character)).runId();
			UUID box = UUID.randomUUID();
			sql("INSERT INTO dalmaru.character_mailbox(event_id, character_id, source_run_id, item_id, quantity) VALUES(?, ?, ?, 'equipment_box.level_15', 1)", box, character, run);
			var claim = new MailboxClaim();
			int before = equipment(character);
			var opened = act(claim, account, generation, 1, character, box);
			var replay = act(claim, account, generation, 1, character, box);
			require(!opened.replayed() && replay.replayed() && opened.result().get("equipmentId").getAsString().equals(box.toString()) && equipment(character) == before + 1 && claimed(box), "Equipment box was not claimed once.");
			var again = act(claim, account, generation, 2, character, box);
			require(!again.replayed() && again.result().equals(opened.result()) && equipment(character) == before + 1, "A second claim created another box.");
			UUID stone = UUID.randomUUID();
			sql("INSERT INTO dalmaru.character_mailbox(event_id, character_id, source_run_id, item_id, quantity) VALUES(?, ?, ?, 'material.ironstone', 2)", stone, character, run);
			int stones = material(character, "material.ironstone");
			var paid = act(claim, account, generation, 3, character, stone);
			var paidAgain = act(claim, account, generation, 4, character, stone);
			require("bag".equals(paid.result().get("destination").getAsString()) && material(character, "material.ironstone") == stones + 2 && paidAgain.result().equals(paid.result()) && material(character, "material.ironstone") == stones + 2, "Material mail was claimed twice.");
			UUID copper = UUID.randomUUID();
			sql("INSERT INTO dalmaru.character_mailbox(event_id, character_id, source_run_id, item_id, quantity) VALUES(?, ?, ?, 'currency.copper', 4)", copper, character, run);
			int wallet = copper(character);
			act(claim, account, generation, 5, character, copper);
			act(claim, account, generation, 6, character, copper);
			require(copper(character) == wallet + 4 && ledger(character) == 4, "Copper mail moved more than once.");
			UUID core = UUID.randomUUID();
			sql("INSERT INTO dalmaru.character_mailbox(event_id, character_id, source_run_id, item_id, quantity) VALUES(?, ?, ?, 'pilot.special.beacon_core', 1)", core, character, run);
			var special = act(claim, account, generation, 8, character, core);
			act(claim, account, generation, 9, character, core);
			require("bag".equals(special.result().get("destination").getAsString()) && material(character, "pilot.special.beacon_core") == 1 && "ACCOUNT".equals(binding(character, "pilot.special.beacon_core")), "Beacon core was not kept once as an account item.");
			sql("UPDATE dalmaru.character_mailbox SET claimed_at=NULL, quantity=2 WHERE event_id=?", copper);
			sql("UPDATE dalmaru.character_wallet SET copper=2147483646 WHERE character_id=?", character);
			reject(() -> act(claim, account, generation, 10, character, copper));
			require(!claimed(copper) && copper(character) == 2147483646, "Copper over the wallet limit was claimed.");
			UUID blocked = UUID.randomUUID();
			sql("INSERT INTO dalmaru.character_mailbox(event_id, character_id, source_run_id, item_id, quantity) VALUES(?, ?, ?, 'supply.recovery', 1)", blocked, character, run);
			int recovery = material(character, "supply.recovery");
			reject(() -> act(claim, account, generation, 7, character, blocked));
			require(!claimed(blocked) && material(character, "supply.recovery") == recovery, "Bound supply left the mailbox.");
			System.out.println("Mailbox claim moves a box or bag item once and returns the saved result PASS");
		} finally {
			if (run != null) sql("DELETE FROM dalmaru.run_instances WHERE run_id=?", run);
			sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?", account);
			EnhancementReplayCheck.cleanup(account);
		}
	}

	private static MailboxClaim.Journaled act(MailboxClaim claim, UUID account, long generation, long request, UUID character, UUID event) throws Exception {
		var body = new JsonObject();
		body.addProperty("eventId", event.toString());
		return claim.actJournaled(account, generation, request, character, event, RequestEnvelope.legacy(account, generation, character, "claim_mail", request, body));
	}

	private static void sql(String query, UUID first, UUID second, UUID third) throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, first);
			statement.setObject(2, second);
			statement.setObject(3, third);
			statement.executeUpdate();
		}
	}

	private static void sql(String query, UUID id) throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			statement.executeUpdate();
		}
	}

	private static int equipment(UUID character) throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.character_equipment WHERE character_id=? AND location='INVENTORY'")) {
			statement.setObject(1, character);
			try (var row = statement.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private static int material(UUID character, String item) throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var statement = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			statement.setObject(1, character);
			statement.setString(2, item);
			try (var row = statement.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private static int copper(UUID character) throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var statement = connection.prepareStatement("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?")) {
			statement.setObject(1, character);
			try (var row = statement.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private static int ledger(UUID character) throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var statement = connection.prepareStatement("SELECT COALESCE(SUM(copper_delta), 0) FROM dalmaru.character_currency_ledger WHERE character_id=? AND reason='mail.claim'")) {
			statement.setObject(1, character);
			try (var row = statement.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private static String binding(UUID character, String item) throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var statement = connection.prepareStatement("SELECT binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			statement.setObject(1, character);
			statement.setString(2, item);
			try (var row = statement.executeQuery()) { return row.next() ? row.getString(1) : ""; }
		}
	}

	private static boolean claimed(UUID event) throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var statement = connection.prepareStatement("SELECT claimed_at IS NOT NULL FROM dalmaru.character_mailbox WHERE event_id=?")) {
			statement.setObject(1, event);
			try (var row = statement.executeQuery()) { return row.next() && row.getBoolean(1); }
		}
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Mailbox accepted a blocked claim.");
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
