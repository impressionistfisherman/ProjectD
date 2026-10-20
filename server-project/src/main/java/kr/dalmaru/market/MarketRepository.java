package kr.dalmaru.market;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.account.SupplyStay;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Stall listings. Exchange search is not sold here. A sale writes the fee ledger once. */
public final class MarketRepository {
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public Journaled stashJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !"stash_market".equals(envelope.operation()) || payload.size() != 2 || !payload.has("materialId") || !payload.has("quantity")) throw new IllegalArgumentException("Invalid market request.");
		int quantity = payload.get("quantity").getAsInt();
		if (quantity < 1) throw new IllegalArgumentException("Invalid market request.");
		String material = payload.get("materialId").getAsString();
		return write(account, generation, correlation, character, envelope, connection -> stash(connection, account, character, material, quantity));
	}

	public Journaled listJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !"list_market".equals(envelope.operation()) || payload.size() != 3 || !payload.has("stashId") || !payload.has("channel") || !payload.has("price")) throw new IllegalArgumentException("Invalid market request.");
		int price = payload.get("price").getAsInt();
		if (price < 1) throw new IllegalArgumentException("Invalid market request.");
		return write(account, generation, correlation, character, envelope, connection -> list(connection, account, character, UUID.fromString(payload.get("stashId").getAsString()), payload.get("channel").getAsString(), price));
	}

	public Journaled buyJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !"buy_market".equals(envelope.operation()) || payload.size() != 2 || !payload.has("listingId") || !payload.has("price")) throw new IllegalArgumentException("Invalid market request.");
		return write(account, generation, correlation, character, envelope, connection -> buy(connection, account, character, UUID.fromString(payload.get("listingId").getAsString()), payload.get("price").getAsInt()));
	}

	public Journaled cancelJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		UUID listing = one(envelope, "cancel_market", "listingId");
		return write(account, generation, correlation, character, envelope, connection -> cancel(connection, account, listing));
	}

	public Journaled claimJournaled(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope) throws SQLException {
		UUID stash = one(envelope, "claim_market", "stashId");
		return write(account, generation, correlation, character, envelope, connection -> claim(connection, account, character, stash));
	}

	private static UUID one(RequestEnvelope envelope, String operation, String field) {
		var payload = envelope == null ? null : envelope.payload();
		if (payload == null || !operation.equals(envelope.operation()) || payload.size() != 1 || !payload.has(field)) throw new IllegalArgumentException("Invalid market request.");
		return UUID.fromString(payload.get(field).getAsString());
	}

	private Journaled write(UUID account, long generation, long correlation, UUID character, RequestEnvelope envelope, Step step) throws SQLException {
		if (correlation < 1 || envelope == null || !character.equals(envelope.activeCharacterId())) throw new IllegalArgumentException("Invalid market request.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, account, correlation, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = step.apply(connection);
				outcome.addProperty("operation", envelope.operation());
				outcome.addProperty("characterId", character.toString());
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception instanceof SQLException sql ? sql : new SQLException(exception); }
		}
	}

	private JsonObject stash(java.sql.Connection connection, UUID account, UUID character, String material, int quantity) throws SQLException {
		var outcome = new JsonObject();
		try (var query = connection.prepareStatement("SELECT quantity, binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=? FOR UPDATE")) {
			query.setObject(1, character);
			query.setString(2, material);
			try (var row = query.executeQuery()) {
				if (!row.next() || row.getInt(1) < quantity) { outcome.addProperty("status", "INSUFFICIENT_MATERIAL"); return outcome; }
				String binding = row.getString(2);
				if (!"FREE".equals(binding)) { outcome.addProperty("status", "BINDING_REJECTED"); return outcome; }
				try { SupplyStay.refuseMove(connection, material, binding); }
				catch (IllegalArgumentException exception) { outcome.addProperty("status", "SUPPLY_STAY"); return outcome; }
			}
		}
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_materials SET quantity=quantity-? WHERE character_id=? AND material_id=? AND binding='FREE' AND quantity>=?")) {
			update.setInt(1, quantity);
			update.setObject(2, character);
			update.setString(3, material);
			update.setInt(4, quantity);
			if (update.executeUpdate() != 1) throw new SQLException("Market stash lost its material.");
		}
		UUID stash = UUID.randomUUID();
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.market_stashes(stash_id, account_id, character_id, material_id, quantity, binding, location) VALUES(?, ?, ?, ?, ?, 'FREE', 'STASH')")) {
			insert.setObject(1, stash);
			insert.setObject(2, account);
			insert.setObject(3, character);
			insert.setString(4, material);
			insert.setInt(5, quantity);
			insert.executeUpdate();
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("stashId", stash.toString());
		outcome.addProperty("location", "STASH");
		return outcome;
	}

	private JsonObject list(java.sql.Connection connection, UUID account, UUID character, UUID stashId, String channel, int price) throws SQLException {
		var outcome = new JsonObject();
		if (!"STALL".equals(channel)) { outcome.addProperty("status", "FORBIDDEN"); return outcome; }
		var stash = lockStash(connection, stashId);
		if (stash == null || !stash.account().equals(account)) { outcome.addProperty("status", "NOT_OWNER"); return outcome; }
		if (!"STASH".equals(stash.location())) { outcome.addProperty("status", "CHANNEL_BUSY"); return outcome; }
		if (openListings(connection, character) >= 10) { outcome.addProperty("status", "FULL"); return outcome; }
		UUID listing = UUID.randomUUID();
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.market_listings(listing_id, stash_id, seller_account, seller_character, channel, price, state, expires_at) VALUES(?, ?, ?, ?, 'STALL', ?, 'OPEN', now() + interval '7 days') RETURNING expires_at")) {
			insert.setObject(1, listing);
			insert.setObject(2, stashId);
			insert.setObject(3, account);
			insert.setObject(4, character);
			insert.setInt(5, price);
			try (var row = insert.executeQuery()) { row.next(); outcome.addProperty("expiresAt", row.getTimestamp(1).toInstant().toString()); }
		}
		try (var update = connection.prepareStatement("UPDATE dalmaru.market_stashes SET location='LISTED' WHERE stash_id=? AND location='STASH'")) {
			update.setObject(1, stashId);
			if (update.executeUpdate() != 1) throw new SQLException("Market list lost its stash.");
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("listingId", listing.toString());
		outcome.addProperty("price", price);
		return outcome;
	}

	private JsonObject buy(java.sql.Connection connection, UUID account, UUID character, UUID listingId, int price) throws SQLException {
		var outcome = new JsonObject();
		var listing = lockListing(connection, listingId);
		if (listing == null || !"OPEN".equals(listing.state()) || listing.price() != price) { outcome.addProperty("status", "CONFLICT"); return outcome; }
		if (listing.expired()) { expire(connection, listing); outcome.addProperty("status", "CONFLICT"); return outcome; }
		if (listing.sellerAccount().equals(account)) { outcome.addProperty("status", "CONFLICT"); return outcome; }
		int buyer = copper(connection, character);
		if (buyer < price) { outcome.addProperty("status", "INSUFFICIENT_FUNDS"); return outcome; }
		copper(connection, listing.sellerCharacter());
		int fee = (int) ((price * 2L + 50) / 100);
		addCopper(connection, character, -price);
		addCopper(connection, listing.sellerCharacter(), price - fee);
		if (fee > 0) ledger(connection, listing.sellerCharacter(), -fee);
		give(connection, character, listing.material(), listing.quantity());
		try (var sold = connection.prepareStatement("UPDATE dalmaru.market_listings SET state='SOLD', fee=? WHERE listing_id=? AND state='OPEN'")) {
			sold.setInt(1, fee);
			sold.setObject(2, listingId);
			if (sold.executeUpdate() != 1) throw new SQLException("Market sale lost its listing.");
		}
		try (var stash = connection.prepareStatement("UPDATE dalmaru.market_stashes SET location='SOLD' WHERE stash_id=? AND location='LISTED'")) {
			stash.setObject(1, listing.stash());
			if (stash.executeUpdate() != 1) throw new SQLException("Market sale lost its stash.");
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("fee", fee);
		outcome.addProperty("location", "INVENTORY");
		outcome.addProperty("buyerCopper", copper(connection, character));
		outcome.addProperty("sellerCopper", copper(connection, listing.sellerCharacter()));
		return outcome;
	}

	private JsonObject cancel(java.sql.Connection connection, UUID account, UUID listingId) throws SQLException {
		var outcome = new JsonObject();
		var listing = lockListing(connection, listingId);
		if (listing == null || !listing.sellerAccount().equals(account)) { outcome.addProperty("status", "NOT_OWNER"); return outcome; }
		if (!"OPEN".equals(listing.state())) { outcome.addProperty("status", "CONFLICT"); return outcome; }
		String state = listing.expired() ? "EXPIRED" : "CANCELLED";
		try (var update = connection.prepareStatement("UPDATE dalmaru.market_listings SET state=? WHERE listing_id=? AND state='OPEN'")) {
			update.setString(1, state);
			update.setObject(2, listingId);
			if (update.executeUpdate() != 1) throw new SQLException("Market cancel lost its listing.");
		}
		try (var stash = connection.prepareStatement("UPDATE dalmaru.market_stashes SET location='STASH' WHERE stash_id=?")) {
			stash.setObject(1, listing.stash());
			stash.executeUpdate();
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("state", state);
		outcome.addProperty("stashId", listing.stash().toString());
		outcome.addProperty("location", "STASH");
		return outcome;
	}

	private JsonObject claim(java.sql.Connection connection, UUID account, UUID character, UUID stashId) throws SQLException {
		var outcome = new JsonObject();
		var stash = lockStash(connection, stashId);
		if (stash == null || !stash.account().equals(account)) { outcome.addProperty("status", "NOT_OWNER"); return outcome; }
		if (!"STASH".equals(stash.location())) { outcome.addProperty("status", "CONFLICT"); return outcome; }
		give(connection, character, stash.material(), stash.quantity());
		try (var update = connection.prepareStatement("UPDATE dalmaru.market_stashes SET location='CLAIMED' WHERE stash_id=? AND location='STASH'")) {
			update.setObject(1, stashId);
			if (update.executeUpdate() != 1) throw new SQLException("Market claim lost its stash.");
		}
		outcome.addProperty("status", "OK");
		outcome.addProperty("location", "INVENTORY");
		outcome.addProperty("quantity", quantity(connection, character, stash.material()));
		return outcome;
	}

	private void expire(java.sql.Connection connection, Listing listing) throws SQLException {
		try (var update = connection.prepareStatement("UPDATE dalmaru.market_listings SET state='EXPIRED' WHERE listing_id=? AND state='OPEN'")) {
			update.setObject(1, listing.id());
			update.executeUpdate();
		}
		try (var stash = connection.prepareStatement("UPDATE dalmaru.market_stashes SET location='STASH' WHERE stash_id=? AND location='LISTED'")) {
			stash.setObject(1, listing.stash());
			stash.executeUpdate();
		}
	}

	private int openListings(java.sql.Connection connection, UUID character) throws SQLException {
		try (var query = connection.prepareStatement("SELECT count(*) FROM dalmaru.market_listings WHERE seller_character=? AND state='OPEN' AND expires_at > clock_timestamp()")) {
			query.setObject(1, character);
			try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
		}
	}

	private int copper(java.sql.Connection connection, UUID character) throws SQLException {
		try (var query = connection.prepareStatement("SELECT copper FROM dalmaru.character_wallet WHERE character_id=? FOR UPDATE")) {
			query.setObject(1, character);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private void addCopper(java.sql.Connection connection, UUID character, int delta) throws SQLException {
		if (delta >= 0) {
			try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_wallet(character_id, copper) VALUES(?, ?) ON CONFLICT(character_id) DO UPDATE SET copper=dalmaru.character_wallet.copper + EXCLUDED.copper")) {
				update.setObject(1, character);
				update.setInt(2, delta);
				update.executeUpdate();
			}
			return;
		}
		try (var update = connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=copper+? WHERE character_id=? AND copper+?>=0")) {
			update.setInt(1, delta);
			update.setObject(2, character);
			update.setInt(3, delta);
			if (update.executeUpdate() != 1) throw new SQLException("Market payment lost its wallet.");
		}
	}

	private void ledger(java.sql.Connection connection, UUID character, int delta) throws SQLException {
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id, character_id, source_run_id, reason, copper_delta) VALUES(?, ?, NULL, 'market.fee', ?)")) {
			insert.setObject(1, UUID.randomUUID());
			insert.setObject(2, character);
			insert.setInt(3, delta);
			insert.executeUpdate();
		}
	}

	private void give(java.sql.Connection connection, UUID character, String material, int quantity) throws SQLException {
		try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, ?, ?, 'FREE') ON CONFLICT(character_id, material_id) DO UPDATE SET quantity=dalmaru.character_materials.quantity + EXCLUDED.quantity WHERE dalmaru.character_materials.binding='FREE'")) {
			update.setObject(1, character);
			update.setString(2, material);
			update.setInt(3, quantity);
			if (update.executeUpdate() != 1) throw new SQLException("Market delivery lost its material.");
		}
	}

	private int quantity(java.sql.Connection connection, UUID character, String material) throws SQLException {
		try (var query = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			query.setObject(1, character);
			query.setString(2, material);
			try (var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
		}
	}

	private Stash lockStash(java.sql.Connection connection, UUID stashId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT account_id, material_id, quantity, location FROM dalmaru.market_stashes WHERE stash_id=? FOR UPDATE")) {
			query.setObject(1, stashId);
			try (var row = query.executeQuery()) {
				return row.next() ? new Stash(row.getObject(1, UUID.class), row.getString(2), row.getInt(3), row.getString(4)) : null;
			}
		}
	}

	private Listing lockListing(java.sql.Connection connection, UUID listingId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT l.stash_id, l.seller_account, l.seller_character, l.price, l.state, l.expires_at <= clock_timestamp(), s.material_id, s.quantity FROM dalmaru.market_listings l JOIN dalmaru.market_stashes s ON s.stash_id=l.stash_id WHERE l.listing_id=? FOR UPDATE OF l, s")) {
			query.setObject(1, listingId);
			try (var row = query.executeQuery()) {
				return row.next() ? new Listing(listingId, row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getObject(3, UUID.class), row.getInt(4), row.getString(5), row.getBoolean(6), row.getString(7), row.getInt(8)) : null;
			}
		}
	}

	private record Stash(UUID account, String material, int quantity, String location) { }
	private record Listing(UUID id, UUID stash, UUID sellerAccount, UUID sellerCharacter, int price, String state, boolean expired, String material, int quantity) { }

	@FunctionalInterface
	private interface Step { JsonObject apply(java.sql.Connection connection) throws SQLException; }
}
