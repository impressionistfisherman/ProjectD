package kr.dalmaru.equipment;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class EquipmentRepository {
    private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
    private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
    private final String password = System.getenv("DALMARU_DB_PASSWORD");
    private final SecureRandom random = new SecureRandom();

    public EnhancementResult enhance(UUID accountId, long generation, long requestId, UUID equipmentId) throws SQLException {
        if (generation < 1 || requestId < 1 || password == null || password.isBlank()) throw new IllegalArgumentException("Invalid enhancement request.");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try {
                try (var request = connection.prepareStatement("SELECT equipment_id, success FROM dalmaru.character_enhancement_requests WHERE account_id=? AND connection_generation=? AND request_id=?")) {
                    request.setObject(1, accountId); request.setLong(2, generation); request.setLong(3, requestId);
                    try (var result = request.executeQuery()) { if (result.next()) { if (!equipmentId.equals(result.getObject(1, UUID.class))) throw new IllegalArgumentException("Request ID was reused."); EquipmentRecord equipment = read(connection, equipmentId); connection.commit(); return new EnhancementResult(equipment, result.getBoolean(2), true); } }
                }
                UUID characterId;
                try (var active = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) { active.setObject(1, accountId); active.setLong(2, generation); try (var result = active.executeQuery()) { if (!result.next() || result.getObject(1) == null) throw new IllegalArgumentException("Account has no active character."); characterId = result.getObject(1, UUID.class); } }
                EquipmentRecord equipment = readForUpdate(connection, equipmentId);
                if (!characterId.equals(equipment.characterId())) throw new IllegalArgumentException("Equipment not owned by active character.");
                if (equipment.enhancement() >= 15) throw new IllegalArgumentException("Equipment is already at maximum enhancement.");
                validateRequirements(connection, equipment);
                boolean success = random.nextInt(100) < EnhancementRules.chance(equipment.enhancement(), equipment.consecutiveFailures());
                int enhancement = success ? equipment.enhancement() + 1 : equipment.enhancement(); int failures = success ? 0 : equipment.consecutiveFailures() + 1;
                try (var update = connection.prepareStatement("UPDATE dalmaru.character_equipment SET enhancement=?, consecutive_failures=? WHERE equipment_id=?")) { update.setInt(1, enhancement); update.setInt(2, failures); update.setObject(3, equipmentId); update.executeUpdate(); }
                try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_enhancement_requests VALUES (?,?,?,?,?,?,?)")) { insert.setObject(1, accountId); insert.setLong(2, generation); insert.setLong(3, requestId); insert.setObject(4, equipmentId); insert.setInt(5, enhancement); insert.setInt(6, failures); insert.setBoolean(7, success); insert.executeUpdate(); }
                connection.commit(); return new EnhancementResult(new EquipmentRecord(equipmentId, characterId, equipment.slotId(), equipment.itemId(), equipment.requiredLevel(), equipment.profession(), enhancement, failures, equipment.bound()), success, false);
            } catch (Exception exception) { connection.rollback(); throw exception; }
        }
    }

    public List<EquipmentRecord> list(UUID characterId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.prepareStatement("SELECT equipment_id, character_id, slot_id, item_id, required_level, COALESCE(profession,''), enhancement, consecutive_failures, bound FROM dalmaru.character_equipment WHERE character_id=? ORDER BY slot_id")) { statement.setObject(1, characterId); try (var result = statement.executeQuery()) { List<EquipmentRecord> equipment = new ArrayList<>(); while (result.next()) equipment.add(record(result)); return equipment; } }
    }
    public List<EquipmentRecord> equipped(UUID characterId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.prepareStatement("SELECT equipment_id, character_id, slot_id, item_id, required_level, COALESCE(profession,''), enhancement, consecutive_failures, bound FROM dalmaru.character_equipment WHERE character_id=? AND location='EQUIPPED' ORDER BY slot_id")) {
            statement.setObject(1, characterId);
            try (var rows = statement.executeQuery()) {
                List<EquipmentRecord> result = new ArrayList<>();
                while (rows.next()) result.add(record(rows));
                return result;
            }
        }
    }
    public RefinementResult refine(UUID accountId, long generation, long requestId, UUID equipmentId) throws SQLException {
        if (generation < 1 || requestId < 1 || password == null || password.isBlank()) throw new IllegalArgumentException("Invalid refinement request.");
        try (var connection = DriverManager.getConnection(url, username, password)) { connection.setAutoCommit(false); try {
            try (var replay = connection.prepareStatement("SELECT equipment_id, grade, failures, success FROM dalmaru.character_refinement_requests WHERE account_id=? AND connection_generation=? AND request_id=?")) { replay.setObject(1, accountId); replay.setLong(2, generation); replay.setLong(3, requestId); try (var rows = replay.executeQuery()) { if (rows.next()) { if (!equipmentId.equals(rows.getObject(1, UUID.class))) throw new IllegalArgumentException("Request ID was reused."); connection.commit(); return new RefinementResult(equipmentId, rows.getInt(2), rows.getInt(3), rows.getBoolean(4), true); } } }
            UUID characterId; try (var active = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) { active.setObject(1, accountId); active.setLong(2, generation); try (var rows = active.executeQuery()) { if (!rows.next() || rows.getObject(1) == null) throw new IllegalArgumentException("No active character."); characterId = rows.getObject(1, UUID.class); } }
            int grade; int failures; try (var equipment = connection.prepareStatement("SELECT refinement_grade, refinement_failures FROM dalmaru.character_equipment WHERE equipment_id=? AND character_id=? FOR UPDATE")) { equipment.setObject(1, equipmentId); equipment.setObject(2, characterId); try (var rows = equipment.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("Equipment not owned by active character."); grade = rows.getInt(1); failures = rows.getInt(2); } }
            if (grade >= 5) throw new IllegalArgumentException("Equipment has maximum refinement.");
            int copper = (grade + 1) * 10; try (var wallet = connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=copper-? WHERE character_id=? AND copper>=?")) { wallet.setInt(1, copper); wallet.setObject(2, characterId); wallet.setInt(3, copper); if (wallet.executeUpdate() != 1) throw new IllegalArgumentException("Insufficient copper."); }
            boolean success = random.nextInt(100) < RefinementRules.chance(grade, failures); int nextGrade = success ? grade + 1 : grade; int nextFailures = success ? 0 : failures + 1;
            try (var update = connection.prepareStatement("UPDATE dalmaru.character_equipment SET refinement_grade=?, refinement_failures=? WHERE equipment_id=?")) { update.setInt(1, nextGrade); update.setInt(2, nextFailures); update.setObject(3, equipmentId); update.executeUpdate(); }
            try (var request = connection.prepareStatement("INSERT INTO dalmaru.character_refinement_requests VALUES (?, ?, ?, ?, ?, ?, ?)")) { request.setObject(1, accountId); request.setLong(2, generation); request.setLong(3, requestId); request.setObject(4, equipmentId); request.setInt(5, nextGrade); request.setInt(6, nextFailures); request.setBoolean(7, success); request.executeUpdate(); }
            try (var ledger = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger (event_id, character_id, source_run_id, reason, copper_delta) VALUES (?, ?, NULL, 'refinement', ?)")) { ledger.setObject(1, UUID.randomUUID()); ledger.setObject(2, characterId); ledger.setInt(3, -copper); ledger.executeUpdate(); }
            connection.commit(); return new RefinementResult(equipmentId, nextGrade, nextFailures, success, false);
        } catch (Exception exception) { connection.rollback(); throw exception; } }
    }
    public InheritanceResult inherit(UUID accountId, long generation, long requestId, UUID targetId, UUID sourceId) throws SQLException {
        if (generation < 1 || requestId < 1 || targetId.equals(sourceId) || password == null || password.isBlank()) throw new IllegalArgumentException("Invalid inheritance request.");
        try (var connection = DriverManager.getConnection(url, username, password)) { connection.setAutoCommit(false); try {
            try (var replay = connection.prepareStatement("SELECT target_equipment_id, source_equipment_id, credit FROM dalmaru.character_inheritance_requests WHERE account_id=? AND connection_generation=? AND request_id=?")) { replay.setObject(1, accountId); replay.setLong(2, generation); replay.setLong(3, requestId); try (var rows = replay.executeQuery()) { if (rows.next()) { if (!targetId.equals(rows.getObject(1, UUID.class)) || !sourceId.equals(rows.getObject(2, UUID.class))) throw new IllegalArgumentException("Request ID was reused."); connection.commit(); return new InheritanceResult(targetId, rows.getInt(3), true); } } }
            UUID characterId; try (var active = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) { active.setObject(1, accountId); active.setLong(2, generation); try (var rows = active.executeQuery()) { if (!rows.next() || rows.getObject(1) == null) throw new IllegalArgumentException("No active character."); characterId = rows.getObject(1, UUID.class); } }
            String targetItem; int targetLevel; try (var target = connection.prepareStatement("SELECT item_id, required_level FROM dalmaru.character_equipment WHERE equipment_id=? AND character_id=? AND location='EQUIPPED' FOR UPDATE")) { target.setObject(1, targetId); target.setObject(2, characterId); try (var rows = target.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("Target must be equipped by active character."); targetItem = rows.getString(1); targetLevel = rows.getInt(2); } }
            String sourceItem; int sourceLevel; int enhancement; int credit; try (var source = connection.prepareStatement("SELECT item_id, required_level, enhancement, inheritance_credit FROM dalmaru.character_equipment WHERE equipment_id=? AND character_id=? AND location='INVENTORY' FOR UPDATE")) { source.setObject(1, sourceId); source.setObject(2, characterId); try (var rows = source.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("Source must be inventory equipment owned by active character."); sourceItem = rows.getString(1); sourceLevel = rows.getInt(2); enhancement = rows.getInt(3); credit = rows.getInt(4); } }
            if (!targetItem.equals(sourceItem) || targetLevel < sourceLevel) throw new IllegalArgumentException("Equipment family or level does not allow inheritance.");
            int gained = (credit + enhancement * 10) * 70 / 100;
            try (var update = connection.prepareStatement("UPDATE dalmaru.character_equipment SET inheritance_credit=inheritance_credit+? WHERE equipment_id=?")) { update.setInt(1, gained); update.setObject(2, targetId); update.executeUpdate(); }
            try (var delete = connection.prepareStatement("DELETE FROM dalmaru.character_equipment WHERE equipment_id=?")) { delete.setObject(1, sourceId); delete.executeUpdate(); }
            try (var request = connection.prepareStatement("INSERT INTO dalmaru.character_inheritance_requests VALUES (?, ?, ?, ?, ?, ?)")) { request.setObject(1, accountId); request.setLong(2, generation); request.setLong(3, requestId); request.setObject(4, targetId); request.setObject(5, sourceId); request.setInt(6, gained); request.executeUpdate(); }
            connection.commit(); return new InheritanceResult(targetId, gained, false);
        } catch (Exception exception) { connection.rollback(); throw exception; } }
    }
    public List<EquipmentRecord> listOrCreate(UUID characterId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password)) { connection.setAutoCommit(false); try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_equipment (equipment_id, character_id, slot_id, item_id, required_level, bound) VALUES (?, ?, ?, ?, 1, TRUE) ON CONFLICT (character_id, slot_id) DO NOTHING")) { for (int slot = 0; slot < 9; slot++) { insert.setObject(1, UUID.randomUUID()); insert.setObject(2, characterId); insert.setInt(3, slot); insert.setString(4, "starter.slot." + slot); insert.addBatch(); } insert.executeBatch(); } connection.commit(); }
        return list(characterId);
    }
    private EquipmentRecord read(Connection connection, UUID id) throws SQLException { try (var statement = connection.prepareStatement("SELECT equipment_id, character_id, slot_id, item_id, required_level, COALESCE(profession,''), enhancement, consecutive_failures, bound FROM dalmaru.character_equipment WHERE equipment_id=?")) { statement.setObject(1, id); try (var result = statement.executeQuery()) { if (!result.next()) throw new IllegalArgumentException("Equipment was not found."); return record(result); } } }
    private void validateRequirements(Connection connection, EquipmentRecord equipment) throws SQLException { try (var statement = connection.prepareStatement("SELECT p.level, COALESCE(j.base_job,'') FROM dalmaru.character_profiles p LEFT JOIN dalmaru.character_professions j ON j.character_id=p.character_id WHERE p.character_id=?")) { statement.setObject(1, equipment.characterId()); try (var result = statement.executeQuery()) { if (!result.next() || result.getInt(1) < equipment.requiredLevel() || (!equipment.profession().isBlank() && !equipment.profession().equals(result.getString(2)))) throw new IllegalArgumentException("Character does not meet equipment requirements."); } } }
    private EquipmentRecord readForUpdate(Connection connection, UUID id) throws SQLException { try (var statement = connection.prepareStatement("SELECT equipment_id, character_id, slot_id, item_id, required_level, COALESCE(profession,''), enhancement, consecutive_failures, bound FROM dalmaru.character_equipment WHERE equipment_id=? FOR UPDATE")) { statement.setObject(1, id); try (var result = statement.executeQuery()) { if (!result.next()) throw new IllegalArgumentException("Equipment was not found."); return record(result); } } }
    private static EquipmentRecord record(java.sql.ResultSet result) throws SQLException { return new EquipmentRecord(result.getObject(1, UUID.class), result.getObject(2, UUID.class), result.getInt(3), result.getString(4), result.getInt(5), result.getString(6), result.getInt(7), result.getInt(8), result.getBoolean(9)); }
}
