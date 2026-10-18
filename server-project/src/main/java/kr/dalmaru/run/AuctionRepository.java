package kr.dalmaru.run;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Server-only auction operations; clients never supply eligibility, time, or settlement results. */
public final class AuctionRepository {
    private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":"
            + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
    private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
    private final String password = System.getenv("DALMARU_DB_PASSWORD");

    public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }
    public record Snapshot(UUID auctionId, UUID runId, String state, AuctionCatalog.Terms terms,
                           Instant startedAt, Instant endsAt, Instant maximumEndAt, UUID highestCharacterId,
                           int highestBid, List<UUID> eligible) {
        public Snapshot { eligible = List.copyOf(eligible); }
        public JsonObject json() {
            var value = new JsonObject(); value.addProperty("auctionId", auctionId.toString()); value.addProperty("runId", runId.toString());
            value.addProperty("state", state); value.add("terms", terms.json()); value.addProperty("startedAt", startedAt.toString());
            value.addProperty("endsAt", endsAt.toString()); value.addProperty("maximumEndAt", maximumEndAt.toString());
            value.addProperty("highestCharacterId", highestCharacterId == null ? null : highestCharacterId.toString());
            value.addProperty("highestBid", highestBid); value.addProperty("reservedCopper", "OPEN".equals(state) ? highestBid : 0);
            value.addProperty("minimumBid", terms.minimumBid(highestBid));
            var members = new JsonArray(); eligible.forEach(id -> members.add(id.toString())); value.add("eligible", members); return value;
        }
    }

    /** Called under the run lock, in the same transaction as eligibility, personal rewards and completion. */
    static void create(Connection connection, UUID runId) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalArgumentException("Auction creation requires completion transaction.");
        var members = new ArrayList<UUID>();
        try (var query = connection.prepareStatement("SELECT character_id FROM dalmaru.run_reward_grants WHERE run_id=? ORDER BY character_id")) {
            query.setObject(1, runId); try (var row = query.executeQuery()) { while (row.next()) members.add(row.getObject(1, UUID.class)); }
        }
        if (members.isEmpty()) return;
        var terms = AuctionCatalog.current(); UUID auctionId = UUID.randomUUID(); Instant now = now(connection);
        try (var insert = connection.prepareStatement("INSERT INTO dalmaru.run_auctions(auction_id,run_id,terms,state,started_at,ends_at,maximum_end_at,settled_at) VALUES(?,?,?::jsonb,?,?,?,?,?)")) {
            insert.setObject(1, auctionId); insert.setObject(2, runId); insert.setString(3, terms.json().toString());
            insert.setString(4, members.size() == 1 ? "DIRECT" : "OPEN"); insert.setTimestamp(5, Timestamp.from(now));
            insert.setTimestamp(6, Timestamp.from(now.plusSeconds(terms.initialSeconds())));
            insert.setTimestamp(7, Timestamp.from(now.plusSeconds(terms.maximumSeconds())));
            insert.setTimestamp(8, members.size() == 1 ? Timestamp.from(now) : null); insert.executeUpdate();
        }
        try (var insert = connection.prepareStatement("INSERT INTO dalmaru.run_auction_members(auction_id,character_id) VALUES(?,?)")) {
            for (UUID character : members) { insert.setObject(1, auctionId); insert.setObject(2, character); insert.addBatch(); } insert.executeBatch();
        }
        if (members.size() == 1) mail(connection, runId, members.getFirst(), terms.itemId(), 1);
    }

    public Journaled bidJournaled(UUID account, long generation, long requestId, UUID character, UUID auction,
                                 int copper, RequestEnvelope envelope) throws SQLException {
        return act(account, generation, requestId, character, auction, copper, envelope, false);
    }
    public Journaled leaveJournaled(UUID account, long generation, long requestId, UUID character, UUID auction,
                                   RequestEnvelope envelope) throws SQLException {
        return act(account, generation, requestId, character, auction, 0, envelope, true);
    }
    private Journaled act(UUID account, long generation, long requestId, UUID character, UUID auction, int copper,
                          RequestEnvelope envelope, boolean leaving) throws SQLException {
        String operation = leaving ? "leave_auction" : "bid_auction";
        if (account == null || character == null || auction == null || generation < 1 || requestId < 1 || envelope == null
                || !character.equals(envelope.activeCharacterId()) || generation != envelope.connectionGeneration()
                || !operation.equals(envelope.operation())) throw new IllegalArgumentException("Invalid auction request.");
        var body = new JsonObject(); body.addProperty("auctionId", auction.toString()); if (!leaving) body.addProperty("copper", copper);
        if (!RequestEnvelope.canonicalJson(body).equals(RequestEnvelope.canonicalJson(envelope.payload())) || (!leaving && copper < 1))
            throw new IllegalArgumentException("Auction envelope does not match.");
        try (var connection = connect()) { connection.setAutoCommit(false); try {
            var stored = RequestJournal.begin(connection, account, requestId, envelope);
            if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
            Snapshot current = lock(connection, auction); requireEligible(connection, auction, character);
            Instant now = now(connection); requireOpen(current, now);
            if (leaving) revoke(connection, current, character);
            else bid(connection, current, character, copper, stored.receipt().eventId(), now);
            var result = lock(connection, auction).json();
            var receipt = RequestJournal.confirm(connection, stored, result);
            connection.commit(); return new Journaled(result, receipt, false);
        } catch (Exception error) { connection.rollback(); throw error; } }
    }

    public Snapshot snapshot(UUID account, long generation, UUID auction) throws SQLException {
        try (var connection = connect()) { connection.setAutoCommit(false); try {
            UUID character;
            try (var query = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) {
                query.setObject(1, account); query.setLong(2, generation); try (var row = query.executeQuery()) {
                    if (!row.next() || row.getObject(1) == null) throw new IllegalArgumentException("No active character.");
                    character = row.getObject(1, UUID.class);
                }
            }
            Snapshot result = lock(connection, auction); requireMember(connection, auction, character);
            connection.commit(); return result;
        } catch (Exception error) { connection.rollback(); throw error; } }
    }
    public Snapshot forRun(UUID account, long generation, UUID run) throws SQLException {
        try (var connection = connect(); var query = connection.prepareStatement("SELECT auction_id FROM dalmaru.run_auctions WHERE run_id=?")) {
            query.setObject(1, run); try (var row = query.executeQuery()) {
                return row.next() ? snapshot(account, generation, row.getObject(1, UUID.class)) : null;
            }
        }
    }
    /** Normal expulsion/voluntary leave only; a network disconnect must not revoke earned eligibility. */
    public Snapshot revokeParticipant(UUID auction, UUID character) throws SQLException {
        try (var connection = connect()) { connection.setAutoCommit(false); try {
            Snapshot current = lock(connection, auction); requireMember(connection, auction, character);
            if ("OPEN".equals(current.state()) && now(connection).isBefore(current.endsAt()) && current.eligible().contains(character))
                revoke(connection, current, character);
            Snapshot result = lock(connection, auction); connection.commit(); return result;
        } catch (Exception error) { connection.rollback(); throw error; } }
    }

    private static void bid(Connection connection, Snapshot auction, UUID character, int copper, UUID event, Instant now) throws SQLException {
        if (copper < auction.terms().minimumBid(auction.highestBid())) throw new IllegalArgumentException("Bid is below the next price.");
        var wallets = new HashSet<UUID>(); wallets.add(character); if (auction.highestCharacterId() != null) wallets.add(auction.highestCharacterId());
        lockWallets(connection, wallets);
        boolean own = character.equals(auction.highestCharacterId());
        int charge = copper - (own ? auction.highestBid() : 0);
        adjustWallet(connection, auction.runId(), character, -charge, "auction.reserve", event);
        if (auction.highestCharacterId() != null && !own)
            refund(connection, auction.runId(), auction.highestCharacterId(), auction.highestBid());
        Instant end = auction.endsAt();
        if (!now.isBefore(end.minusSeconds(auction.terms().extensionWindowSeconds()))) {
            end = end.plusSeconds(auction.terms().extensionSeconds()); if (end.isAfter(auction.maximumEndAt())) end = auction.maximumEndAt();
        }
        try (var update = connection.prepareStatement("UPDATE dalmaru.run_auctions SET highest_character_id=?,highest_bid=?,ends_at=? WHERE auction_id=?")) {
            update.setObject(1, character); update.setInt(2, copper); update.setTimestamp(3, Timestamp.from(end)); update.setObject(4, auction.auctionId()); update.executeUpdate();
        }
    }
    private static void revoke(Connection connection, Snapshot auction, UUID character) throws SQLException {
        if (character.equals(auction.highestCharacterId())) {
            lockWallets(connection, Set.of(character));
            refund(connection, auction.runId(), character, auction.highestBid());
            try (var update = connection.prepareStatement("UPDATE dalmaru.run_auctions SET highest_character_id=NULL,highest_bid=0 WHERE auction_id=?")) {
                update.setObject(1, auction.auctionId()); update.executeUpdate();
            }
        }
        try (var update = connection.prepareStatement("UPDATE dalmaru.run_auction_members SET revoked=TRUE WHERE auction_id=? AND character_id=?")) {
            update.setObject(1, auction.auctionId()); update.setObject(2, character); update.executeUpdate();
        }
        if (auction.eligible().size() == 1) finish(connection, auction.auctionId(), "EMPTY");
    }

    /** Persistent auction lock makes repeated calls and recovery after restart safe. */
    public Snapshot settle(UUID auction) throws SQLException {
        try (var connection = connect()) { connection.setAutoCommit(false); try {
            Snapshot current = lock(connection, auction);
            if ("OPEN".equals(current.state()) && !now(connection).isBefore(current.endsAt())) {
                var recipients = new ArrayList<>(current.eligible());
                if (current.highestCharacterId() != null) {
                    if (!recipients.remove(current.highestCharacterId())) throw new IllegalStateException("Auction winner is ineligible.");
                    mail(connection, current.runId(), current.highestCharacterId(), current.terms().itemId(), 1);
                    if (!recipients.isEmpty()) Collections.rotate(recipients, -Math.floorMod(auction.hashCode(), recipients.size()));
                    distribute(connection, current.runId(), recipients, current.highestBid()); finish(connection, auction, "WON");
                } else {
                    distribute(connection, current.runId(), recipients, current.terms().saleCopper());
                    finish(connection, auction, recipients.isEmpty() ? "EMPTY" : "UNSOLD");
                }
            }
            Snapshot result = lock(connection, auction); connection.commit(); return result;
        } catch (Exception error) { connection.rollback(); throw error; } }
    }
    public int settleExpired() throws SQLException {
        var expired = new ArrayList<UUID>();
        try (var connection = connect(); var query = connection.prepareStatement("SELECT auction_id FROM dalmaru.run_auctions WHERE state='OPEN' AND ends_at<=clock_timestamp() ORDER BY ends_at LIMIT 64"); var rows = query.executeQuery()) {
            while (rows.next()) expired.add(rows.getObject(1, UUID.class));
        }
        for (UUID auction : expired) settle(auction);
        return expired.size();
    }

    private static Snapshot lock(Connection connection, UUID auction) throws SQLException {
        try (var query = connection.prepareStatement("SELECT run_id,state,terms::text,started_at,ends_at,maximum_end_at,highest_character_id,highest_bid FROM dalmaru.run_auctions WHERE auction_id=? FOR UPDATE")) {
            query.setObject(1, auction); try (var row = query.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Auction not found.");
                var members = new ArrayList<UUID>();
                try (var participants = connection.prepareStatement("SELECT character_id FROM dalmaru.run_auction_members WHERE auction_id=? AND NOT revoked ORDER BY character_id")) {
                    participants.setObject(1, auction); try (var result = participants.executeQuery()) { while (result.next()) members.add(result.getObject(1, UUID.class)); }
                }
                return new Snapshot(auction, row.getObject(1, UUID.class), row.getString(2), AuctionCatalog.parse(row.getString(3)),
                        row.getTimestamp(4).toInstant(), row.getTimestamp(5).toInstant(), row.getTimestamp(6).toInstant(),
                        row.getObject(7, UUID.class), row.getInt(8), members);
            }
        }
    }
    private static void requireMember(Connection connection, UUID auction, UUID character) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.run_auction_members WHERE auction_id=? AND character_id=?")) {
            query.setObject(1, auction); query.setObject(2, character); try (var row = query.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Not an auction participant.");
            }
        }
    }
    private static void requireEligible(Connection connection, UUID auction, UUID character) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.run_auction_members WHERE auction_id=? AND character_id=? AND NOT revoked")) {
            query.setObject(1, auction); query.setObject(2, character); try (var row = query.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Not eligible for the auction.");
            }
        }
    }
    private static void requireOpen(Snapshot auction, Instant now) {
        if (!"OPEN".equals(auction.state()) || !now.isBefore(auction.endsAt())) throw new IllegalArgumentException("Auction is closed.");
    }
    private static void lockWallets(Connection connection, Set<UUID> characters) throws SQLException {
        try (var query = connection.prepareStatement("SELECT character_id FROM dalmaru.character_wallet WHERE character_id=ANY(?) ORDER BY character_id FOR UPDATE")) {
            query.setArray(1, connection.createArrayOf("uuid", characters.toArray())); int count = 0;
            try (var rows = query.executeQuery()) { while (rows.next()) count++; }
            if (count != characters.size()) throw new IllegalArgumentException("Wallet not found.");
        }
    }
    private static void adjustWallet(Connection connection, UUID run, UUID character, int delta, String reason, UUID event) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=copper+? WHERE character_id=? AND copper::bigint+? BETWEEN 0 AND 2147483647")) {
            update.setInt(1, delta); update.setObject(2, character); update.setInt(3, delta);
            if (update.executeUpdate() != 1) throw new IllegalArgumentException("Insufficient funds or wallet capacity.");
        }
        try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id,character_id,source_run_id,reason,copper_delta) VALUES(?,?,?,?,?)")) {
            insert.setObject(1, event); insert.setObject(2, character); insert.setObject(3, run);
            insert.setString(4, reason + ":" + event); insert.setInt(5, delta); insert.executeUpdate();
        }
    }
    private static void distribute(Connection connection, UUID run, List<UUID> recipients, int copper) throws SQLException {
        for (int i = 0; i < recipients.size(); i++) {
            int amount = copper / recipients.size() + (i < copper % recipients.size() ? 1 : 0);
            if (amount > 0) mail(connection, run, recipients.get(i), "currency.copper", amount);
        }
    }
    private static void refund(Connection connection, UUID run, UUID character, int copper) throws SQLException {
        int balance;
        try (var query = connection.prepareStatement("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?")) {
            query.setObject(1, character); try (var row = query.executeQuery()) { row.next(); balance = row.getInt(1); }
        }
        UUID event = UUID.randomUUID(); int immediate = (int) Math.min(copper, (long) Integer.MAX_VALUE - balance);
        if (immediate > 0) adjustWallet(connection, run, character, immediate, "auction.refund", event);
        if (immediate < copper) mail(connection, run, character, "currency.copper", (long) copper - immediate, event);
    }
    private static void mail(Connection connection, UUID run, UUID character, String item, long quantity) throws SQLException {
        mail(connection, run, character, item, quantity, UUID.randomUUID());
    }
    private static void mail(Connection connection, UUID run, UUID character, String item, long quantity, UUID event) throws SQLException {
        if (quantity < 1) throw new IllegalArgumentException("Mail quantity must be positive.");
        long remaining = quantity;
        for (long part = 0; remaining > 0; part++) {
            int amount = (int) Math.min(remaining, Integer.MAX_VALUE);
            UUID id = part == 0 ? event : UUID.nameUUIDFromBytes((event + ":" + part).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_mailbox(event_id,character_id,source_run_id,item_id,quantity) VALUES(?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING")) {
                insert.setObject(1, id); insert.setObject(2, character); insert.setObject(3, run); insert.setString(4, item); insert.setInt(5, amount);
                if (insert.executeUpdate() == 0) try (var verify = connection.prepareStatement("SELECT 1 FROM dalmaru.character_mailbox WHERE event_id=? AND character_id=? AND source_run_id=? AND item_id=? AND quantity=?")) {
                    verify.setObject(1, id); verify.setObject(2, character); verify.setObject(3, run); verify.setString(4, item); verify.setInt(5, amount);
                    try (var row = verify.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Mail event was reused with different contents."); }
                }
            }
            remaining -= amount;
        }
    }
    private static void finish(Connection connection, UUID auction, String state) throws SQLException {
        try (var update = connection.prepareStatement("UPDATE dalmaru.run_auctions SET state=?,settled_at=clock_timestamp() WHERE auction_id=?")) {
            update.setString(1, state); update.setObject(2, auction); update.executeUpdate();
        }
    }
    private static Instant now(Connection connection) throws SQLException {
        try (var query = connection.createStatement(); var row = query.executeQuery("SELECT clock_timestamp()")) { row.next(); return row.getTimestamp(1).toInstant(); }
    }
    private Connection connect() throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        return DriverManager.getConnection(url, username, password);
    }
}
