package kr.dalmaru;

import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;

/** S07 opens level 60 only after releaseLevelCap. A fresh JVM stays at the product cap of 20. */
public final class P5GrowthReleaseCapCheck {
    public static void main(String[] args) throws Exception {
        FixtureDatabase.databaseName();
        FixtureMigrations.apply();
        UUID account = UUID.randomUUID();
        try {
            check(CharacterRepository.TRIAL_LEVEL_CAP == 20, "Product constant changed");
            check(CharacterRepository.activeLevelCap() == 20, "Cap opened before release");
            check(!CharacterRepository.advancementEnabled(), "Advancement opened before release");
            long generation = new AccountRepository().openSession(account).connectionGeneration();
            var characters = new CharacterRepository();
            UUID character = characters.create(account, 1, "s07_" + account.toString().substring(0, 8)).characterId();
            characters.selectActive(account, generation, 1, character);
            check(characters.growthSnapshot(account, generation, character).levelCap() == 20, "Snapshot opened before release");
            check(characters.grantExperience(character, 1_000_000).level() == 20, "Experience passed 20 before release");
            CharacterRepository.releaseLevelCap();
            check(CharacterRepository.TRIAL_LEVEL_CAP == 20, "Release rewrote the product constant");
            check(CharacterRepository.activeLevelCap() == 60, "Release did not open 60");
            check(CharacterRepository.advancementEnabled(), "Release did not open advancement");
            check(characters.growthSnapshot(account, generation, character).levelCap() == 60, "Snapshot stayed closed");
            check(characters.grantExperience(character, 1_000_000).level() == 60, "Experience did not reach 60");
            System.out.println("PASS P5 growth release cap 20 then 60");
        } finally {
            cleanup(account);
        }
    }

    private static void cleanup(UUID account) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + FixtureDatabase.databaseName(), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"))) {
            for (String sql : List.of(
                    "UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
                    "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
                    "DELETE FROM dalmaru.character_creation_requests WHERE account_id=?",
                    "DELETE FROM dalmaru.character_selection_requests WHERE account_id=?",
                    "DELETE FROM dalmaru.characters WHERE account_id=?",
                    "DELETE FROM dalmaru.accounts WHERE account_id=?")) {
                try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, account); statement.executeUpdate(); }
            }
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
