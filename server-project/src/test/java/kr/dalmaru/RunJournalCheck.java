package kr.dalmaru;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunRepository;

public final class RunJournalCheck {
    public static void main(String[] args) throws Exception {
        if (!"oscar_p2_fixture".equals(System.getenv("DALMARU_DB_NAME"))) throw new IllegalStateException("Refusing to run outside oscar_p2_fixture.");
        UUID account = UUID.randomUUID();
        try {
            var session = new AccountRepository().openSession(account);
            long generation = session.connectionGeneration();
            var character = new CharacterRepository().create(account, 1, "runj_" + account.toString().substring(0, 8));
            new CharacterRepository().selectActive(account, generation, 2, character.characterId());
            new CharacterRepository().grantExperience(character.characterId(), 8000);
            var runs = new RunRepository();
            var first = runs.enter(account, generation, 1, RunDifficulty.FIRST_STEP, List.of(character.characterId()));
            var replay = runs.enter(account, generation, 1, RunDifficulty.FIRST_STEP, List.of(character.characterId()));
            if (!replay.replayed() || !first.runId().equals(replay.runId()) || count("SELECT count(*) FROM dalmaru.operation_requests WHERE account_id=? AND operation='enter_run'", account) != 1 || count("SELECT count(*) FROM dalmaru.run_instances r JOIN dalmaru.run_participants p ON p.run_id=r.run_id WHERE p.character_id=?", character.characterId()) != 1) throw new AssertionError("Run entry replayed as a second run.");
            boolean rejected = false;
            try { runs.enter(account, generation, 1, RunDifficulty.TRAINING, List.of(character.characterId())); }
            catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Changed run entry body was accepted.");
            System.out.println("Run entry journal replay PASS");
        } finally { cleanup(account); }
    }
    private static long count(String sql, UUID id) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(sql)) { statement.setObject(1, id); try (var row = statement.executeQuery()) { row.next(); return row.getLong(1); } }
    }
    private static void cleanup(UUID account) throws Exception {
        try (var connection = connection()) {
            for (String sql : List.of("UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?", "DELETE FROM dalmaru.operation_requests WHERE account_id=?", "DELETE FROM dalmaru.run_participants WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.run_instances WHERE run_id IN (SELECT run_id FROM dalmaru.run_requests WHERE account_id=?)", "DELETE FROM dalmaru.run_requests WHERE account_id=?", "DELETE FROM dalmaru.character_run_weekly_clears WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_selection_requests WHERE account_id=?", "DELETE FROM dalmaru.character_creation_requests WHERE account_id=?", "DELETE FROM dalmaru.characters WHERE account_id=?", "DELETE FROM dalmaru.accounts WHERE account_id=?")) {
                try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, account); statement.executeUpdate(); }
            }
        }
    }
    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/oscar_p2_fixture", System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
    }
}
