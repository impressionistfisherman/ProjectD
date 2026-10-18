package kr.dalmaru.mail;

import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.account.SupplyStay;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Claims one mailbox row into the bag or inventory. A claimed row returns the same result. */
public final class MailboxClaim {
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public Journaled actJournaled(UUID accountId, long generation, long requestId, UUID characterId, UUID eventId, RequestEnvelope envelope) throws SQLException {
		if (generation < 1 || requestId < 1 || eventId == null || envelope == null || !characterId.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid mailbox claim.");
		var body = envelope.payload();
		if (body.size() != 1 || !eventId.toString().equals(text(body, "eventId"))) throw new IllegalArgumentException("Mailbox envelope does not match.");
		try (var connection = connect()) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, accountId, requestId, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = claim(connection, accountId, characterId, eventId);
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private JsonObject claim(Connection connection, UUID accountId, UUID characterId, UUID eventId) throws SQLException {
		try (var owner = connection.prepareStatement("SELECT 1 FROM dalmaru.characters WHERE character_id=? AND account_id=? FOR UPDATE")) {
			owner.setObject(1, characterId);
			owner.setObject(2, accountId);
			try (var row = owner.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Unknown mailbox character."); }
		}
		Mail mail = lock(connection, characterId, eventId);
		if (mail.claimed()) return outcome(mail);
		String binding = "pilot.special.beacon_core".equals(mail.itemId()) ? "ACCOUNT" : "FREE";
		SupplyStay.refuseMove(connection, mail.itemId(), binding);
		if (mail.itemId().startsWith("equipment_box.")) grantEquipment(connection, characterId, eventId, mail);
		else if ("currency.copper".equals(mail.itemId())) grantCopper(connection, characterId, mail);
		else grantMaterial(connection, characterId, mail, binding);
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_mailbox SET claimed_at=clock_timestamp() WHERE event_id=? AND claimed_at IS NULL")) {
			update.setObject(1, eventId);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Mailbox claim changed.");
		}
		return outcome(mail);
	}

	private void grantEquipment(Connection connection, UUID characterId, UUID eventId, Mail mail) throws SQLException {
		if (mail.quantity() != 1) throw new IllegalArgumentException("Invalid equipment box.");
		int level = level(mail.itemId());
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_equipment(equipment_id, character_id, slot_id, item_id, required_level, bound, location, binding) VALUES(?, ?, NULL, ?, ?, TRUE, 'INVENTORY', 'CHARACTER')")) {
			insert.setObject(1, eventId);
			insert.setObject(2, characterId);
			insert.setString(3, mail.itemId());
			insert.setInt(4, level);
			insert.executeUpdate();
		}
	}

	private void grantCopper(Connection connection, UUID characterId, Mail mail) throws SQLException {
		int wallet = 0;
		try (var query = connection.prepareStatement("SELECT copper FROM dalmaru.character_wallet WHERE character_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			try (var row = query.executeQuery()) { if (row.next()) wallet = row.getInt(1); }
		}
		if (wallet + (long) mail.quantity() > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid mailbox copper.");
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=? WHERE character_id=?")) {
			update.setInt(1, wallet + mail.quantity());
			update.setObject(2, characterId);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Invalid mailbox copper.");
		}
		try (var ledger = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id, character_id, source_run_id, reason, copper_delta) VALUES(?, ?, ?, 'mail.claim', ?)")) {
			ledger.setObject(1, UUID.randomUUID());
			ledger.setObject(2, characterId);
			ledger.setObject(3, mail.runId());
			ledger.setInt(4, mail.quantity());
			ledger.executeUpdate();
		}
	}

	private void grantMaterial(Connection connection, UUID characterId, Mail mail, String binding) throws SQLException {
		int owned = 0;
		String held = binding;
		try (var query = connection.prepareStatement("SELECT quantity, binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=? FOR UPDATE")) {
			query.setObject(1, characterId);
			query.setString(2, mail.itemId());
			try (var row = query.executeQuery()) { if (row.next()) { owned = row.getInt(1); held = row.getString(2); } }
		}
		if (!binding.equals(held) || owned + (long) mail.quantity() > Integer.MAX_VALUE) throw new IllegalArgumentException("Material binding does not match the mailbox.");
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, ?) ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=EXCLUDED.quantity WHERE dalmaru.character_materials.binding=EXCLUDED.binding")) {
			update.setObject(1, characterId);
			update.setString(2, mail.itemId());
			update.setInt(3, owned + mail.quantity());
			update.setString(4, binding);
			if (update.executeUpdate() != 1) throw new IllegalArgumentException("Material binding does not match the mailbox.");
		}
	}

	private Mail lock(Connection connection, UUID characterId, UUID eventId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT item_id, quantity, source_run_id, claimed_at FROM dalmaru.character_mailbox WHERE event_id=? AND character_id=? FOR UPDATE")) {
			query.setObject(1, eventId);
			query.setObject(2, characterId);
			try (var row = query.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Unknown mailbox row.");
				return new Mail(eventId, row.getString(1), row.getInt(2), row.getObject(3, UUID.class), row.getTimestamp(4) != null);
			}
		}
	}

	private static JsonObject outcome(Mail mail) {
		var outcome = new JsonObject();
		outcome.addProperty("eventId", mail.eventId().toString());
		outcome.addProperty("itemId", mail.itemId());
		outcome.addProperty("quantity", mail.quantity());
		if (mail.itemId().startsWith("equipment_box.")) {
			outcome.addProperty("destination", "equipment");
			outcome.addProperty("equipmentId", mail.eventId().toString());
		} else outcome.addProperty("destination", "currency.copper".equals(mail.itemId()) ? "wallet" : "bag");
		return outcome;
	}

	private static int level(String itemId) {
		int cut = itemId.lastIndexOf('_');
		if (cut < 0 || cut == itemId.length() - 1) throw new IllegalArgumentException("Invalid equipment box.");
		try {
			int level = Integer.parseInt(itemId.substring(cut + 1));
			if (level < 1 || level > 60) throw new IllegalArgumentException("Invalid equipment box.");
			return level;
		} catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid equipment box.", error); }
	}

	private static String text(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Invalid mailbox body.");
		return body.get(key).getAsString();
	}

	private Connection connect() throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		return DriverManager.getConnection(url, username, password);
	}

	private record Mail(UUID eventId, String itemId, int quantity, UUID runId, boolean claimed) { }
}
