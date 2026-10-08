package kr.dalmaru.run;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public final class RunRepository {
    private static final String CONTENT = "run.bongsudae";
    private final RunRewardRepository rewards = new RunRewardRepository();
    private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
    private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
    private final String password = System.getenv("DALMARU_DB_PASSWORD");

    public RunRecord enter(UUID accountId, long generation, long requestId, RunDifficulty difficulty, List<UUID> participants) throws SQLException {
        if (generation < 1 || requestId < 1 || participants.isEmpty() || participants.size() > 4 || password == null || password.isBlank()) throw new IllegalArgumentException("Invalid run entry.");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try {
                UUID active = activeCharacter(connection, accountId, generation);
                if (!participants.contains(active) || participants.stream().distinct().count() != participants.size()) throw new IllegalArgumentException("Active character must enter exactly once.");
                RunRecord replay = replay(connection, accountId, generation, requestId, "ENTER"); if (replay != null) { connection.commit(); return replay; }
                for (UUID characterId : participants) validateParticipant(connection, characterId, difficulty);
                int tier = participants.size() == 1 ? 1 : participants.size() == 2 ? 2 : 4;
                LocalDate week = weekId();
                for (UUID characterId : participants) reserveAvailable(connection, characterId, difficulty, week);
                UUID runId = UUID.randomUUID();
                try (var insert = connection.prepareStatement("INSERT INTO dalmaru.run_instances (run_id, content_id, difficulty, week_id, party_size, scaling_tier, state) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')")) { insert.setObject(1, runId); insert.setString(2, CONTENT); insert.setString(3, difficulty.name()); insert.setObject(4, week); insert.setInt(5, participants.size()); insert.setInt(6, tier); insert.executeUpdate(); }
                try (var insert = connection.prepareStatement("INSERT INTO dalmaru.run_participants (run_id, character_id) VALUES (?, ?)")) { for (UUID characterId : participants) { insert.setObject(1, runId); insert.setObject(2, characterId); insert.addBatch(); } insert.executeBatch(); }
                request(connection, accountId, generation, requestId, "ENTER", runId, "ACTIVE"); connection.commit(); return new RunRecord(runId, difficulty, participants.size(), tier, "ACTIVE", false);
            } catch (Exception exception) { connection.rollback(); throw exception; }
        }
    }
    public RunRecord finish(UUID accountId, long generation, long requestId, UUID runId, boolean success) throws SQLException {
        if (generation < 1 || requestId < 1 || password == null || password.isBlank()) throw new IllegalArgumentException("Invalid run result.");
        try (var connection = DriverManager.getConnection(url, username, password)) { connection.setAutoCommit(false); try {
            UUID active = activeCharacter(connection, accountId, generation); RunRecord replay = replay(connection, accountId, generation, requestId, success ? "COMPLETE" : "FAIL"); if (replay != null) { connection.commit(); return replay; }
            RunRecord run = lockRun(connection, runId); if (!"ACTIVE".equals(run.state()) || !participant(connection, runId, active)) throw new IllegalArgumentException("Run cannot be finished.");
            if (success) {
                rewards.grant(connection, runId, run.difficulty());
                try (var increment = connection.prepareStatement("UPDATE dalmaru.character_run_weekly_clears SET clear_count=clear_count+1 WHERE character_id IN (SELECT character_id FROM dalmaru.run_participants WHERE run_id=?) AND content_id=? AND difficulty=? AND week_id=(SELECT week_id FROM dalmaru.run_instances WHERE run_id=?)")) { increment.setObject(1, runId); increment.setString(2, CONTENT); increment.setString(3, run.difficulty().name()); increment.setObject(4, runId); increment.executeUpdate(); }
            }
            String state = success ? "COMPLETED" : "FAILED"; try (var update = connection.prepareStatement("UPDATE dalmaru.run_instances SET state=?, finished_at=CURRENT_TIMESTAMP WHERE run_id=?")) { update.setString(1, state); update.setObject(2, runId); update.executeUpdate(); }
            request(connection, accountId, generation, requestId, success ? "COMPLETE" : "FAIL", runId, state); connection.commit(); return new RunRecord(runId, run.difficulty(), run.partySize(), run.scalingTier(), state, false);
        } catch (Exception exception) { connection.rollback(); throw exception; } }
    }
    public RunRecord active(UUID characterId) throws SQLException {
        return resume(characterId);
    }
    public RunRecord pauseIfUnattended(UUID characterId, List<UUID> connectedCharacters) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password)) { connection.setAutoCommit(false); try {
            RunRecord run = activeForUpdate(connection, characterId); if (run == null || !"ACTIVE".equals(run.state()) || hasConnectedParticipant(connection, run.runId(), connectedCharacters)) { connection.commit(); return null; }
            try (var update = connection.prepareStatement("UPDATE dalmaru.run_instances SET state='PAUSED', paused_at=CURRENT_TIMESTAMP, pause_expires_at=CURRENT_TIMESTAMP + INTERVAL '5 minutes' WHERE run_id=?")) { update.setObject(1, run.runId()); update.executeUpdate(); }
            connection.commit(); return new RunRecord(run.runId(), run.difficulty(), run.partySize(), run.scalingTier(), "PAUSED", false);
        } catch (Exception exception) { connection.rollback(); throw exception; } }
    }
    public RunRecord resume(UUID characterId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password)) { connection.setAutoCommit(false); try {
            RunRecord run = activeForUpdate(connection, characterId); if (run == null || !"PAUSED".equals(run.state())) { connection.commit(); return run; }
            try (var update = connection.prepareStatement("UPDATE dalmaru.run_instances SET state='ACTIVE', paused_at=NULL, pause_expires_at=NULL WHERE run_id=? AND deadline_at>CURRENT_TIMESTAMP")) { update.setObject(1, run.runId()); if (update.executeUpdate() != 1) throw new IllegalArgumentException("Run has expired."); }
            connection.commit(); return new RunRecord(run.runId(), run.difficulty(), run.partySize(), run.scalingTier(), "ACTIVE", false);
        } catch (Exception exception) { connection.rollback(); throw exception; } }
    }
    public int expireTimedOut() throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password); var update = connection.prepareStatement("UPDATE dalmaru.run_instances SET state='FAILED', finished_at=CURRENT_TIMESTAMP WHERE state IN ('ACTIVE', 'PAUSED') AND (deadline_at<=CURRENT_TIMESTAMP OR pause_expires_at<=CURRENT_TIMESTAMP)")) { return update.executeUpdate(); }
    }
    public List<RunReward> rewards(UUID runId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password)) { return rewards.rewards(connection, runId); }
    }
    private UUID activeCharacter(Connection connection, UUID accountId, long generation) throws SQLException { try (var statement = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) { statement.setObject(1, accountId); statement.setLong(2, generation); try (var result = statement.executeQuery()) { if (!result.next() || result.getObject(1) == null) throw new IllegalArgumentException("No active character."); return result.getObject(1, UUID.class); } } }
    private void validateParticipant(Connection connection, UUID characterId, RunDifficulty difficulty) throws SQLException { try (var statement = connection.prepareStatement("SELECT level FROM dalmaru.character_profiles WHERE character_id=?")) { statement.setObject(1, characterId); try (var result = statement.executeQuery()) { if (!result.next() || result.getInt(1) < difficulty.requiredLevel()) throw new IllegalArgumentException("Level requirement not met."); } } if (difficulty == RunDifficulty.TRAINING && !clearedFirstStep(connection, characterId)) throw new IllegalArgumentException("Training is locked."); }
    private boolean clearedFirstStep(Connection connection, UUID characterId) throws SQLException { try (var statement = connection.prepareStatement("SELECT 1 FROM dalmaru.character_run_weekly_clears WHERE character_id=? AND content_id=? AND difficulty='FIRST_STEP' AND clear_count>0 LIMIT 1")) { statement.setObject(1, characterId); statement.setString(2, CONTENT); try (var result = statement.executeQuery()) { return result.next(); } } }
    private void reserveAvailable(Connection connection, UUID characterId, RunDifficulty difficulty, LocalDate week) throws SQLException { try (var active = connection.prepareStatement("SELECT 1 FROM dalmaru.run_participants p JOIN dalmaru.run_instances r ON r.run_id=p.run_id WHERE p.character_id=? AND r.state='ACTIVE' FOR UPDATE")) { active.setObject(1, characterId); try (var result = active.executeQuery()) { if (result.next()) throw new IllegalArgumentException("Character already has an active run."); } } try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_run_weekly_clears (character_id, content_id, difficulty, week_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) { insert.setObject(1, characterId); insert.setString(2, CONTENT); insert.setString(3, difficulty.name()); insert.setObject(4, week); insert.executeUpdate(); } try (var statement = connection.prepareStatement("SELECT clear_count FROM dalmaru.character_run_weekly_clears WHERE character_id=? AND content_id=? AND difficulty=? AND week_id=? FOR UPDATE")) { statement.setObject(1, characterId); statement.setString(2, CONTENT); statement.setString(3, difficulty.name()); statement.setObject(4, week); try (var result = statement.executeQuery()) { result.next(); if (result.getInt(1) >= 3) throw new IllegalArgumentException("Weekly clear limit reached."); } } }
    private RunRecord replay(Connection connection, UUID accountId, long generation, long requestId, String action) throws SQLException { try (var statement = connection.prepareStatement("SELECT r.run_id, r.difficulty, r.party_size, r.scaling_tier, q.state FROM dalmaru.run_requests q JOIN dalmaru.run_instances r ON r.run_id=q.run_id WHERE q.account_id=? AND q.connection_generation=? AND q.request_id=?")) { statement.setObject(1, accountId); statement.setLong(2, generation); statement.setLong(3, requestId); try (var result = statement.executeQuery()) { if (!result.next()) return null; if (!action.equals(result.getString(5).equals("ACTIVE") ? "ENTER" : result.getString(5).equals("COMPLETED") ? "COMPLETE" : "FAIL")) throw new IllegalArgumentException("Request ID was reused."); return new RunRecord(result.getObject(1, UUID.class), RunDifficulty.valueOf(result.getString(2)), result.getInt(3), result.getInt(4), result.getString(5), true); } } }
    private RunRecord lockRun(Connection connection, UUID runId) throws SQLException { try (var statement = connection.prepareStatement("SELECT run_id, difficulty, party_size, scaling_tier, state FROM dalmaru.run_instances WHERE run_id=? FOR UPDATE")) { statement.setObject(1, runId); try (var result = statement.executeQuery()) { if (!result.next()) throw new IllegalArgumentException("Run was not found."); return new RunRecord(result.getObject(1, UUID.class), RunDifficulty.valueOf(result.getString(2)), result.getInt(3), result.getInt(4), result.getString(5), false); } } }
    private RunRecord activeForUpdate(Connection connection, UUID characterId) throws SQLException { try (var statement = connection.prepareStatement("SELECT r.run_id, r.difficulty, r.party_size, r.scaling_tier, r.state FROM dalmaru.run_instances r JOIN dalmaru.run_participants p ON p.run_id=r.run_id WHERE p.character_id=? AND r.state IN ('ACTIVE', 'PAUSED') ORDER BY r.started_at DESC LIMIT 1 FOR UPDATE")) { statement.setObject(1, characterId); try (var result = statement.executeQuery()) { return result.next() ? new RunRecord(result.getObject(1, UUID.class), RunDifficulty.valueOf(result.getString(2)), result.getInt(3), result.getInt(4), result.getString(5), false) : null; } } }
    private boolean hasConnectedParticipant(Connection connection, UUID runId, List<UUID> connectedCharacters) throws SQLException { if (connectedCharacters.isEmpty()) return false; try (var statement = connection.prepareStatement("SELECT 1 FROM dalmaru.run_participants WHERE run_id=? AND character_id = ANY (?) LIMIT 1")) { statement.setObject(1, runId); statement.setArray(2, connection.createArrayOf("uuid", connectedCharacters.toArray())); try (var result = statement.executeQuery()) { return result.next(); } } }
    private boolean participant(Connection connection, UUID runId, UUID characterId) throws SQLException { try (var statement = connection.prepareStatement("SELECT 1 FROM dalmaru.run_participants WHERE run_id=? AND character_id=?")) { statement.setObject(1, runId); statement.setObject(2, characterId); try (var result = statement.executeQuery()) { return result.next(); } } }
    private void request(Connection connection, UUID accountId, long generation, long requestId, String action, UUID runId, String state) throws SQLException { try (var statement = connection.prepareStatement("INSERT INTO dalmaru.run_requests VALUES (?, ?, ?, ?, ?, ?)")) { statement.setObject(1, accountId); statement.setLong(2, generation); statement.setLong(3, requestId); statement.setString(4, action); statement.setObject(5, runId); statement.setString(6, state); statement.executeUpdate(); } }
    static LocalDate weekId() { LocalDate date = LocalDate.now(ZoneId.of("Asia/Seoul")); int offset = (date.getDayOfWeek().getValue() - DayOfWeek.THURSDAY.getValue() + 7) % 7; return date.minusDays(offset); }
}
