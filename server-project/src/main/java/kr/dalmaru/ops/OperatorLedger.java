package kr.dalmaru.ops;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** Operator grant and revoke. The target character need not be online or selected. */
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

    public record Adjustment(UUID operatorAccountId, long requestNo, UUID targetAccountId, UUID characterId, UUID eventId, String kind, String itemId, int delta, String reason, int appliedBalance, int currentBalance, boolean replayed) {}

    public String submit(UUID operator, UUID character, String kind, String itemId, int delta, long request, String reason) {
        if (operator == null || character == null) return "대상 캐릭터를 찾지 못했습니다.";
        try {
            Adjustment result = adjust(operator, character, kind, itemId, delta, request, reason);
            return (result.replayed() ? "이미 처리된 요청입니다. " : "처리했습니다. ") + result.kind() + " " + result.delta() + ", 적용 잔액 " + result.appliedBalance() + ", 현재 잔액 " + result.currentBalance();
        } catch (IllegalArgumentException error) {
            return switch (String.valueOf(error.getMessage())) {
                case "Insufficient balance." -> "잔액이 부족해 회수하지 않았습니다.";
                case "Request ID was reused with another owner or body" -> "같은 요청 번호의 내용이 다릅니다.";
                case "Character was not found." -> "대상 캐릭터를 찾지 못했습니다.";
                default -> "지급·회수를 거부했습니다.";
            };
        } catch (SQLException error) { return "지급·회수를 저장하지 못했습니다."; }
    }

    public Adjustment adjust(UUID operator, UUID character, String kind, String itemId, int delta, long request, String reason) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        if (operator == null || character == null || request < 1) throw new IllegalArgumentException("Adjustment is not allowed.");
        Body body = Body.read(kind, itemId, delta, reason);
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try {
                UUID targetAccount = owner(connection, character);
                lock(connection, operator, targetAccount);
                Adjustment stored = stored(connection, operator, request);
                if (stored != null) {
                    if (!character.equals(stored.characterId()) || !body.same(stored)) throw new IllegalArgumentException("Request ID was reused with another owner or body");
                    int current = balance(connection, character, body);
                    connection.commit();
                    return new Adjustment(operator, request, stored.targetAccountId(), character, stored.eventId(), body.kind, body.itemId, body.delta, body.reason, stored.appliedBalance(), current, true);
                }
                int applied = apply(connection, character, body);
                UUID event = UUID.randomUUID();
                try (var insert = connection.prepareStatement("INSERT INTO dalmaru.operator_adjustments(operator_account_id,request_no,target_account_id,character_id,event_id,kind,item_id,delta,reason,result_balance) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    insert.setObject(1, operator); insert.setLong(2, request); insert.setObject(3, targetAccount); insert.setObject(4, character); insert.setObject(5, event);
                    insert.setString(6, body.kind); insert.setString(7, body.itemId); insert.setInt(8, body.delta); insert.setString(9, body.reason); insert.setInt(10, applied);
                    insert.executeUpdate();
                }
                if ("copper".equals(body.kind)) try (var currency = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id,character_id,source_run_id,reason,copper_delta) VALUES(?,?,NULL,?,?)")) {
                    currency.setObject(1, event); currency.setObject(2, character); currency.setString(3, body.reason); currency.setInt(4, body.delta); currency.executeUpdate();
                }
                connection.commit();
                return new Adjustment(operator, request, targetAccount, character, event, body.kind, body.itemId, body.delta, body.reason, applied, applied, false);
            } catch (RuntimeException | SQLException error) { connection.rollback(); throw error; }
        }
    }

    private static UUID owner(Connection connection, UUID character) throws SQLException {
        try (var query = connection.prepareStatement("SELECT account_id FROM dalmaru.characters WHERE character_id=?")) {
            query.setObject(1, character);
            try (var row = query.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Character was not found."); return row.getObject(1, UUID.class); }
        }
    }

    private static void lock(Connection connection, UUID operator, UUID target) throws SQLException {
        UUID first = operator.compareTo(target) <= 0 ? operator : target;
        UUID second = first.equals(operator) ? target : operator;
        hold(connection, first);
        if (!first.equals(second)) hold(connection, second);
    }

    private static void hold(Connection connection, UUID account) throws SQLException {
        try (var query = connection.prepareStatement("SELECT account_id FROM dalmaru.accounts WHERE account_id=? FOR UPDATE")) {
            query.setObject(1, account);
            try (var row = query.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Character was not found."); }
        }
    }

    private static Adjustment stored(Connection connection, UUID operator, long request) throws SQLException {
        try (var query = connection.prepareStatement("SELECT target_account_id,character_id,event_id,kind,item_id,delta,reason,result_balance FROM dalmaru.operator_adjustments WHERE operator_account_id=? AND request_no=? FOR UPDATE")) {
            query.setObject(1, operator); query.setLong(2, request);
            try (var row = query.executeQuery()) {
                if (!row.next()) return null;
                return new Adjustment(operator, request, row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getObject(3, UUID.class), row.getString(4), row.getString(5), row.getInt(6), row.getString(7), row.getInt(8), row.getInt(8), true);
            }
        }
    }

    private static int apply(Connection connection, UUID character, Body body) throws SQLException {
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

    private static int balance(Connection connection, UUID character, Body body) throws SQLException {
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

    private record Body(String kind, String itemId, int delta, String reason) {
        static Body read(String kind, String itemId, int delta, String reason) {
            String cleaned = reason == null ? "" : reason.trim();
            if (kind == null || !kind.matches("copper|material|skill_points") || delta == 0 || delta < -1_000_000 || delta > 1_000_000 || cleaned.isEmpty() || cleaned.length() > 200) throw new IllegalArgumentException("Adjustment is not allowed.");
            if ("material".equals(kind) ? itemId == null || !itemId.matches("[a-z][a-z0-9._]{0,63}") : itemId != null) throw new IllegalArgumentException("Adjustment item does not match its kind.");
            return new Body(kind, itemId, delta, cleaned);
        }
        boolean same(Adjustment stored) { return kind.equals(stored.kind()) && java.util.Objects.equals(itemId, stored.itemId()) && delta == stored.delta() && reason.equals(stored.reason()); }
    }
}
