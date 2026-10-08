package kr.dalmaru;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunRepository;

public final class RunLifecycleCheck {
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000015");
    private RunLifecycleCheck() { }
    public static void main(String[] args) throws Exception {
        cleanup();
        try {
            var accounts = new AccountRepository(); var characters = new CharacterRepository(); var runs = new RunRepository();
            var session = accounts.openSession(ACCOUNT); var character = characters.create(ACCOUNT, 1, "달마루_수명시험15"); characters.selectActive(ACCOUNT, session.connectionGeneration(), 1501, character.characterId()); characters.grantExperience(character.characterId(), 8000);
            var run = runs.enter(ACCOUNT, session.connectionGeneration(), 1502, RunDifficulty.FIRST_STEP, List.of(character.characterId()));
            if (!"PAUSED".equals(runs.pauseIfUnattended(character.characterId(), List.of()).state())) throw new IllegalStateException("Unattended run was not paused.");
            if (!"ACTIVE".equals(runs.resume(character.characterId()).state())) throw new IllegalStateException("Paused run was not resumed.");
            expire(run.runId()); if (runs.expireTimedOut() != 1 || runs.active(character.characterId()) != null) throw new IllegalStateException("Expired run was not failed.");
            System.out.println("Run pause, resume, and timeout lifecycle verified.");
        } finally { cleanup(); }
    }
    private static void expire(UUID runId) throws Exception { try (var connection = connection(); var statement = connection.prepareStatement("UPDATE dalmaru.run_instances SET deadline_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE run_id=?")) { statement.setObject(1, runId); statement.executeUpdate(); } }
    private static void cleanup() throws Exception { if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return; try (var connection = connection()) { for (String sql : new String[] { "UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?", "DELETE FROM dalmaru.run_requests WHERE account_id=?", "DELETE FROM dalmaru.run_instances WHERE run_id IN (SELECT p.run_id FROM dalmaru.run_participants p JOIN dalmaru.characters c ON c.character_id=p.character_id WHERE c.account_id=?)", "DELETE FROM dalmaru.character_run_weekly_clears WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_selection_requests WHERE account_id=?", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.characters WHERE account_id=?", "DELETE FROM dalmaru.accounts WHERE account_id=?" }) try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); } } }
    private static java.sql.Connection connection() throws Exception { Class.forName("org.postgresql.Driver"); return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD")); }
}
