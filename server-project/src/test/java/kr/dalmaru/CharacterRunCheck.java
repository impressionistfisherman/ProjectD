package kr.dalmaru;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunRepository;

public final class CharacterRunCheck {
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private CharacterRunCheck() { }
    public static void main(String[] args) throws Exception {
        cleanup(); var accounts = new AccountRepository(); var characters = new CharacterRepository(); var runs = new RunRepository();
        var session = accounts.openSession(ACCOUNT); var character = characters.create(ACCOUNT, 1, "달마루_공략시험13"); characters.selectActive(ACCOUNT, session.connectionGeneration(), 1001, character.characterId()); characters.grantExperience(character.characterId(), 8000);
        var first = runs.enter(ACCOUNT, session.connectionGeneration(), 2001, RunDifficulty.FIRST_STEP, List.of(character.characterId()));
        if (first.partySize() != 1 || first.scalingTier() != 1 || !"ACTIVE".equals(first.state())) throw new IllegalStateException("First-step run was not reserved.");
        if (runs.active(character.characterId()) == null || !first.runId().equals(runs.active(character.characterId()).runId())) throw new IllegalStateException("Active run was not restored.");
        var replay = runs.enter(ACCOUNT, session.connectionGeneration(), 2001, RunDifficulty.FIRST_STEP, List.of(character.characterId()));
        if (!replay.replayed() || !first.runId().equals(replay.runId())) throw new IllegalStateException("Entry replay changed run.");
        var completed = runs.finish(ACCOUNT, session.connectionGeneration(), 2002, first.runId(), true); if (!"COMPLETED".equals(completed.state())) throw new IllegalStateException("Clear was not completed.");
        var training = runs.enter(ACCOUNT, session.connectionGeneration(), 2003, RunDifficulty.TRAINING, List.of(character.characterId()));
        var failed = runs.finish(ACCOUNT, session.connectionGeneration(), 2004, training.runId(), false); if (!"FAILED".equals(failed.state())) throw new IllegalStateException("Failure was not released.");
        if (!"ACTIVE".equals(runs.enter(ACCOUNT, session.connectionGeneration(), 2005, RunDifficulty.TRAINING, List.of(character.characterId())).state())) throw new IllegalStateException("Failure incorrectly consumed weekly entry.");
        cleanup(); System.out.println("Character run reservations verified.");
    }
    private static void cleanup() throws Exception { if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return; try (var connection = connection()) { for (String sql : new String[] { "UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?", "DELETE FROM dalmaru.run_requests WHERE account_id=?", "DELETE FROM dalmaru.run_instances WHERE run_id IN (SELECT p.run_id FROM dalmaru.run_participants p JOIN dalmaru.characters c ON c.character_id=p.character_id WHERE c.account_id=?)", "DELETE FROM dalmaru.character_run_weekly_clears WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_enhancement_requests WHERE account_id=?", "DELETE FROM dalmaru.character_equipment WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_selection_requests WHERE account_id=?", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.characters WHERE account_id=?", "DELETE FROM dalmaru.accounts WHERE account_id=?" }) try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); } } }
    private static java.sql.Connection connection() throws Exception { Class.forName("org.postgresql.Driver"); return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD")); }
}
