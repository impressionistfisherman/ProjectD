package kr.dalmaru;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunRepository;

public final class RunRewardCheck {
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private RunRewardCheck() { }
    public static void main(String[] args) throws Exception {
        cleanup();
        try {
            var accounts = new AccountRepository(); var characters = new CharacterRepository(); var runs = new RunRepository();
            var session = accounts.openSession(ACCOUNT); var character = characters.create(ACCOUNT, 1, "달마루_보상시험14");
            characters.selectActive(ACCOUNT, session.connectionGeneration(), 1401, character.characterId()); characters.grantExperience(character.characterId(), 8000);
            var first = runs.enter(ACCOUNT, session.connectionGeneration(), 1402, RunDifficulty.FIRST_STEP, List.of(character.characterId()));
            int beforeReward = count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", character.characterId());
            runs.finish(ACCOUNT, session.connectionGeneration(), 1403, first.runId(), true);
            var reward = runs.rewards(first.runId()).getFirst();
            if (reward.copper() != 70 || reward.ironstone() != 3 || reward.beaconToken() != 1 || !"equipment_box.level_15".equals(reward.equipmentBox())) throw new IllegalStateException("First-step reward is incorrect.");
            runs.finish(ACCOUNT, session.connectionGeneration(), 1403, first.runId(), true);
            if (count("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", character.characterId()) != beforeReward + 70) throw new IllegalStateException("Reward replay changed wallet.");
            if (count("SELECT COUNT(*) FROM dalmaru.character_currency_ledger WHERE character_id=?", character.characterId()) != 1) throw new IllegalStateException("Reward replay changed currency ledger.");
            var training = runs.enter(ACCOUNT, session.connectionGeneration(), 1404, RunDifficulty.TRAINING, List.of(character.characterId()));
            runs.finish(ACCOUNT, session.connectionGeneration(), 1405, training.runId(), true);
            var trainingReward = runs.rewards(training.runId()).getFirst();
            if (trainingReward.copper() != 100 || trainingReward.ironstone() != 5 || trainingReward.beaconToken() != 2 || !"equipment_box.level_20".equals(trainingReward.equipmentBox())) throw new IllegalStateException("Training reward is incorrect.");
            System.out.println("Run reward wallet, materials, mailbox, and replay protection verified.");
        } finally { cleanup(); }
    }
    private static int count(String sql, UUID characterId) throws Exception { try (var connection = connection(); var statement = connection.prepareStatement(sql)) { statement.setObject(1, characterId); try (var result = statement.executeQuery()) { if (!result.next()) throw new IllegalStateException("Reward row is missing."); return result.getInt(1); } } }
    private static void cleanup() throws Exception { if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return; try (var connection = connection()) { for (String sql : new String[] { "UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?", "DELETE FROM dalmaru.run_requests WHERE account_id=?", "DELETE FROM dalmaru.run_instances WHERE run_id IN (SELECT p.run_id FROM dalmaru.run_participants p JOIN dalmaru.characters c ON c.character_id=p.character_id WHERE c.account_id=?)", "DELETE FROM dalmaru.character_run_first_rewards WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_run_weekly_clears WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_selection_requests WHERE account_id=?", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.characters WHERE account_id=?", "DELETE FROM dalmaru.accounts WHERE account_id=?" }) try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); } } }
    private static java.sql.Connection connection() throws Exception { Class.forName("org.postgresql.Driver"); return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD")); }
}
