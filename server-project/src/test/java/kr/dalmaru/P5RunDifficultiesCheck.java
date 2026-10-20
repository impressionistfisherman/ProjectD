package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.combat.CombatUnits;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.run.BossScaling;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunEncounterRules;
import kr.dalmaru.run.RunRepository;

public final class P5RunDifficultiesCheck {
    public static void main(String[] args) throws Exception {
        FixtureDatabase.databaseName();
        FixtureMigrations.apply();
        var content = ContentCatalog.current();
        var rules = content.mechanics().boss();
        check(rules.trialHealthScale() == 1.1 && rules.extremeHealthScale() == 1.2, "Health scales were not read");
        check(rules.trialDamageScale() == 1.05 && rules.extremeDamageScale() == 1.1, "Damage scales were not read");
        check(rules.trialPostureScale() == 1.05 && rules.extremePostureScale() == 1.1, "Posture scales were not read");
        check(RunEncounterRules.healthFactor(RunDifficulty.FIRST_STEP, rules) == 1 && RunEncounterRules.healthFactor(RunDifficulty.TRAINING, rules) == 1.38, "Existing health factors moved");
        check(RunEncounterRules.damageFactor(RunDifficulty.TRAINING, rules) == 1.15 && RunEncounterRules.postureFactor(RunDifficulty.TRAINING, rules) == 1.2, "Training factors moved");
        check(RunEncounterRules.healthFactor(RunDifficulty.TRIAL, rules) == 1.1 && RunEncounterRules.healthFactor(RunDifficulty.EXTREME, rules) == 1.2, "New health factors ignored the file");
        check(RunDifficulty.FIRST_STEP.revives() == 5 && RunDifficulty.TRAINING.revives() == 3 && RunDifficulty.TRIAL.revives() == 2 && RunDifficulty.EXTREME.revives() == 1, "Revive counts changed");
        check(RunDifficulty.TRIAL.weeklyLimit() == 3 && RunDifficulty.TRIAL.durationMillis() == 45L * 60L * 1000L && RunDifficulty.EXTREME.requiredLevel() == 60, "Dungeon limit moved");
        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        check(runtime.contains("difficulty.revives()") && runtime.contains("existing.difficulty.revives()") && runtime.contains("DUNGEON_DURATION_MILLIS"), "Runtime still hardcodes revives or the deadline");
        double extreme = BossScaling.health(content, RunDifficulty.EXTREME, content.encounter().finalBossHealth(), 4);
        check(extreme > 1024 * 20 && CombatUnits.cappedEngineMaximum(extreme) == 1024f, "Boss bar cap moved");
        boolean rejectedHealth = false;
        try { CombatUnits.maximumHealth(extreme); } catch (IllegalArgumentException expected) { rejectedHealth = true; }
        check(rejectedHealth, "Player health accepted a value above 1024");
        same(BossScaling.health(content, RunDifficulty.FIRST_STEP, content.encounter().gatekeeperHealth(), 1), 9300, "Gatekeeper solo HP");

        UUID account = UUID.randomUUID();
        try {
            long generation = new AccountRepository().openSession(account).connectionGeneration();
            var characters = new CharacterRepository();
            UUID character = characters.create(account, 1, "s07r_" + account.toString().substring(0, 8)).characterId();
            characters.selectActive(account, generation, 1, character);
            var runs = new RunRepository();
            level(character, 20);
            reject("Training is locked.", () -> runs.enter(account, generation, 2, RunDifficulty.TRAINING, List.of(character)));
            level(character, 29);
            reject("Level requirement not met.", () -> runs.enter(account, generation, 3, RunDifficulty.TRIAL, List.of(character)));
            level(character, 30);
            var trial = runs.enter(account, generation, 4, RunDifficulty.TRIAL, List.of(character));
            check("DUNGEON".equals(text("SELECT kind FROM dalmaru.run_instances WHERE run_id=?", trial.runId())), "Trial was not a dungeon");
            check(Math.abs(number("SELECT EXTRACT(EPOCH FROM (deadline_at-started_at)) FROM dalmaru.run_instances WHERE run_id=?", trial.runId()) - 2700) < 1, "Trial deadline is not 45 minutes");
            runs.saveReturn(trial.runId(), character, "minecraft:overworld", 3, 64, 5, "survival");
            check(runs.failUnattendedWipe(trial.runId()), "Trial did not fail");
            var back = runs.pendingReturn(character);
            check(back != null && back.runId().equals(trial.runId()) && back.x() == 3 && back.y() == 64 && back.z() == 5, "Return recovery did not keep the saved spot");
            check(runs.acknowledgeReturn(account, generation, character, trial.runId()) && runs.pendingReturn(character) == null, "Return acknowledgement did not clear");
            sql("UPDATE dalmaru.character_run_weekly_clears SET clear_count=3 WHERE character_id=? AND difficulty='TRIAL'", character);
            reject("Weekly clear limit reached.", () -> runs.enter(account, generation, 5, RunDifficulty.TRIAL, List.of(character)));
            level(character, 59);
            reject("Level requirement not met.", () -> runs.enter(account, generation, 6, RunDifficulty.EXTREME, List.of(character)));
            level(character, 60);
            var extremeRun = runs.enter(account, generation, 7, RunDifficulty.EXTREME, List.of(character));
            check("DUNGEON".equals(text("SELECT kind FROM dalmaru.run_instances WHERE run_id=?", extremeRun.runId())), "Extreme was not a dungeon");
            reject("Invalid run entry.", () -> runs.enter(account, generation, 8, RunDifficulty.FIRST_STEP, List.of(character, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
            rejectSql(() -> sql("UPDATE dalmaru.run_instances SET kind='RAID' WHERE run_id=?", extremeRun.runId()));
            rejectSql(() -> sql("UPDATE dalmaru.run_instances SET party_size=5 WHERE run_id=?", extremeRun.runId()));
            sql("INSERT INTO dalmaru.character_run_first_rewards (character_id, difficulty) VALUES (?, 'FIRST_STEP')", character);
            sql("INSERT INTO dalmaru.character_run_first_rewards (character_id, difficulty) VALUES (?, 'TRIAL')", character);
            rejectSql(() -> sql("INSERT INTO dalmaru.character_run_first_rewards (character_id, difficulty) VALUES (?, 'RAID')", character));
            check(runs.failUnattendedWipe(extremeRun.runId()), "Extreme did not fail");
            level(character, 15);
            var first = runs.enter(account, generation, 9, RunDifficulty.FIRST_STEP, List.of(character));
            check(first.difficulty() == RunDifficulty.FIRST_STEP && "DUNGEON".equals(text("SELECT kind FROM dalmaru.run_instances WHERE run_id=?", first.runId())), "First step no longer enters");
            System.out.println("PASS P5 trial/extreme dungeon, cap gates, weekly 3, 45 minutes, closed raid");
        } finally {
            cleanup(account);
        }
    }

    private static void level(UUID character, int level) throws Exception { sql("UPDATE dalmaru.character_profiles SET level=? WHERE character_id=?", level, character); }
    private static void reject(String message, Attempt attempt) {
        try { attempt.run(); throw new AssertionError("Accepted: " + message); }
        catch (IllegalArgumentException error) { if (!message.equals(error.getMessage())) throw new AssertionError(error.getMessage()); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void rejectSql(Attempt attempt) {
        try { attempt.run(); throw new AssertionError("Raid or old difficulty value was stored"); }
        catch (SQLException expected) { if (!"23514".equals(expected.getSQLState())) throw new AssertionError(expected.getSQLState()); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void sql(String query, Object... values) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(query)) { bind(statement, values); statement.executeUpdate(); }
    }
    private static String text(String query, Object... values) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(query)) { bind(statement, values); try (var row = statement.executeQuery()) { row.next(); return row.getString(1); } }
    }
    private static double number(String query, Object... values) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(query)) { bind(statement, values); try (var row = statement.executeQuery()) { row.next(); return row.getDouble(1); } }
    }
    private static void bind(java.sql.PreparedStatement statement, Object[] values) throws Exception {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Integer number) statement.setInt(i + 1, number);
            else statement.setObject(i + 1, values[i]);
        }
    }
    private static void cleanup(UUID account) throws Exception {
        try (var connection = connection()) {
            for (String query : List.of(
                    "UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
                    "DELETE FROM dalmaru.operation_requests WHERE account_id=?",
                    "DELETE FROM dalmaru.run_party_invites WHERE run_id IN (SELECT run_id FROM dalmaru.run_requests WHERE account_id=?)",
                    "DELETE FROM dalmaru.run_party_control WHERE run_id IN (SELECT run_id FROM dalmaru.run_requests WHERE account_id=?)",
                    "DELETE FROM dalmaru.run_member_lifecycle WHERE run_id IN (SELECT run_id FROM dalmaru.run_requests WHERE account_id=?)",
                    "DELETE FROM dalmaru.run_participants WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
                    "DELETE FROM dalmaru.character_run_first_rewards WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
                    "DELETE FROM dalmaru.character_run_weekly_clears WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
                    "DELETE FROM dalmaru.run_instances WHERE run_id IN (SELECT run_id FROM dalmaru.run_requests WHERE account_id=?)",
                    "DELETE FROM dalmaru.run_requests WHERE account_id=?",
                    "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
                    "DELETE FROM dalmaru.character_selection_requests WHERE account_id=?",
                    "DELETE FROM dalmaru.character_creation_requests WHERE account_id=?",
                    "DELETE FROM dalmaru.characters WHERE account_id=?",
                    "DELETE FROM dalmaru.accounts WHERE account_id=?")) {
                try (var statement = connection.prepareStatement(query)) { statement.setObject(1, account); statement.executeUpdate(); }
            }
        }
    }
    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + FixtureDatabase.databaseName(), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
    }
    private static void same(double actual, double expected, String message) { if (Math.abs(actual - expected) > 1e-6) throw new AssertionError(message + " " + actual); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Attempt { void run() throws Exception; }
}
