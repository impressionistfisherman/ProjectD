package kr.dalmaru.ops;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

/** Idempotent operator grant and revoke. The target is the session's active character. */
public final class OperatorLedger {
    private final String url;
    private final String username;
    private final String password;

    public OperatorLedger() {
        String host = System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost");
        String port = System.getenv().getOrDefault("DALMARU_DB_PORT", "15432");
        String database = System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
        url = "jdbc:postgresql://" + host + ":" + port + "/" + database + "?connectTimeout=1&loginTimeout=1&socketTimeout=2&options=-c%20statement_timeout%3D250ms%20-c%20lock_timeout%3D200ms";
        username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
        password = System.getenv("DALMARU_DB_PASSWORD");
        try { Class.forName("org.postgresql.Driver"); } catch (ClassNotFoundException error) { throw new ExceptionInInitializerError(error); }
    }

    public record Adjustment(UUID requestId, UUID eventId, String kind, String itemId, int delta, String reason, int appliedBalance, int currentBalance, boolean replayed) {}

    public Adjustment adjust(UUID account, long legacyRequest, RequestEnvelope envelope) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        if (!"operator_adjust".equals(envelope.operation())) throw new IllegalArgumentException("Not an operator adjustment.");
        Request body = Request.read(envelope.payload());
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try {
                var stored = RequestJournal.begin(connection, account, legacyRequest, envelope);
                if (stored.result() != null) {
                    Request saved = Request.read(stored.result());
                    int current = balance(connection, envelope.activeCharacterId(), saved);
                    connection.commit();
                    return new Adjustment(stored.receipt().requestId(), stored.receipt().eventId(), saved.kind, saved.itemId, saved.delta, saved.reason, saved.balance, current, true);
                }
                int applied = apply(connection, envelope.activeCharacterId(), body);
                var outcome = body.outcome(applied);
                var receipt = RequestJournal.confirm(connection, stored, outcome);
                try (var insert = connection.prepareStatement("INSERT INTO dalmaru.operator_adjustments(request_id,account_id,character_id,event_id,kind,item_id,delta,reason,result_balance) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    insert.setObject(1, receipt.requestId()); insert.setObject(2, account); insert.setObject(3, envelope.activeCharacterId()); insert.setObject(4, receipt.eventId());
                    insert.setString(5, body.kind); insert.setString(6, body.itemId); insert.setInt(7, body.delta); insert.setString(8, body.reason); insert.setInt(9, applied);
                    insert.executeUpdate();
                }
                if ("copper".equals(body.kind)) try (var currency = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id,character_id,source_run_id,reason,copper_delta) VALUES(?,?,NULL,?,?)")) {
                    currency.setObject(1, receipt.eventId()); currency.setObject(2, envelope.activeCharacterId()); currency.setString(3, body.reason); currency.setInt(4, body.delta); currency.executeUpdate();
                }
                connection.commit();
                return new Adjustment(receipt.requestId(), receipt.eventId(), body.kind, body.itemId, body.delta, body.reason, applied, applied, false);
            } catch (RuntimeException | SQLException error) { connection.rollback(); throw error; }
        }
    }

    private static int apply(Connection connection, UUID character, Request body) throws SQLException {
        try {
            return switch (body.kind) {
                case "copper" -> returning(connection, "INSERT INTO dalmaru.character_wallet(character_id,copper) VALUES(?,?) ON CONFLICT(character_id) DO UPDATE SET copper=dalmaru.character_wallet.copper+EXCLUDED.copper WHERE dalmaru.character_wallet.copper+EXCLUDED.copper>=0 RETURNING copper", character, null, body.delta);
                case "skill_points" -> returning(connection, "UPDATE dalmaru.character_profiles SET unspent_skill_points=unspent_skill_points+? WHERE character_id=? AND unspent_skill_points+?>=0 AND unspent_skill_points+?<=32767 RETURNING unspent_skill_points", character, "", body.delta);
                case "material" -> returning(connection, "INSERT INTO dalmaru.character_materials(character_id,material_id,quantity,binding) VALUES(?,?,?,'CHARACTER') ON CONFLICT(character_id,material_id) DO UPDATE SET quantity=dalmaru.character_materials.quantity+EXCLUDED.quantity WHERE dalmaru.character_materials.quantity+EXCLUDED.quantity>=0 RETURNING quantity", character, body.itemId, body.delta);
                default -> throw new IllegalArgumentException("Unknown adjustment kind.");
            };
        } catch (SQLException error) {
            if ("23514".equals(error.getSQLState())) throw new IllegalArgumentException("Insufficient balance.");
            throw error;
        }
    }

    private static int returning(Connection connection, String sql, UUID character, String itemId, int delta) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            if ("".equals(itemId)) { statement.setInt(1, delta); statement.setObject(2, character); statement.setInt(3, delta); statement.setInt(4, delta); }
            else if (itemId == null) { statement.setObject(1, character); statement.setInt(2, delta); }
            else { statement.setObject(1, character); statement.setString(2, itemId); statement.setInt(3, delta); }
            try (var row = statement.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Insufficient balance."); return row.getInt(1); }
        }
    }

    private static int balance(Connection connection, UUID character, Request body) throws SQLException {
        String sql = switch (body.kind) {
            case "copper" -> "SELECT COALESCE((SELECT copper FROM dalmaru.character_wallet WHERE character_id=?),0)";
            case "skill_points" -> "SELECT unspent_skill_points FROM dalmaru.character_profiles WHERE character_id=?";
            case "material" -> "SELECT COALESCE((SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?),0)";
            default -> throw new IllegalArgumentException("Unknown adjustment kind.");
        };
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, character);
            if ("material".equals(body.kind)) statement.setString(2, body.itemId);
            try (var row = statement.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Character profile is missing."); return row.getInt(1); }
        }
    }

    private record Request(String kind, String itemId, int delta, String reason, int balance) {
        static Request read(JsonObject payload) {
            if (payload.get("kind") == null || payload.get("delta") == null || payload.get("reason") == null) throw new IllegalArgumentException("Adjustment body is incomplete.");
            String kind = payload.get("kind").getAsString();
            String itemId = payload.get("itemId") == null || payload.get("itemId").isJsonNull() ? null : payload.get("itemId").getAsString();
            BigDecimal number = payload.get("delta").getAsBigDecimal();
            if (number.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("Adjustment delta must be a whole number.");
            int delta = number.intValueExact();
            String reason = payload.get("reason").getAsString().trim();
            int balance = payload.get("balance") == null || payload.get("balance").isJsonNull() ? -1 : payload.get("balance").getAsInt();
            if (!kind.matches("copper|material|skill_points") || delta == 0 || delta < -1_000_000 || delta > 1_000_000 || reason.isEmpty() || reason.length() > 200) throw new IllegalArgumentException("Adjustment is not allowed.");
            if ("material".equals(kind) ? itemId == null || !itemId.matches("[a-z][a-z0-9._]{0,63}") : itemId != null) throw new IllegalArgumentException("Adjustment item does not match its kind.");
            return new Request(kind, itemId, delta, reason, balance);
        }
        JsonObject outcome(int applied) {
            var result = new JsonObject();
            result.addProperty("kind", kind);
            if (itemId != null) result.addProperty("itemId", itemId);
            result.addProperty("delta", delta);
            result.addProperty("reason", reason);
            result.addProperty("balance", applied);
            return result;
        }
    }
}
