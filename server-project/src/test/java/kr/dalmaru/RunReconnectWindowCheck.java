package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunEncounterRules;
import kr.dalmaru.run.RunRepository;

/**
 * P4-S07 reconnect window: a disconnected member may return while now <= disconnectAt+180s, the whole-run pause is
 * capped at the same 180 seconds in both the runtime and the database, and the old 5-minute and 10-second linger
 * paths no longer disagree.
 */
public final class RunReconnectWindowCheck {
    private RunReconnectWindowCheck() { }

    public static void main(String[] args) throws Exception {
        // Rule boundaries in milliseconds (A09: now <= disconnectAt + 180s).
        long at = 1_000_000L;
        check(RunEncounterRules.RECONNECT_WINDOW_MS == 180_000L, "Window is not 180 seconds");
        check(RunEncounterRules.reconnectAllowed(0, at + 999_999L), "A connected member was treated as expired");
        check(RunEncounterRules.reconnectAllowed(at, at) && RunEncounterRules.reconnectAllowed(at, at + 179_999L) && RunEncounterRules.reconnectAllowed(at, at + 180_000L), "Reconnect refused inside the window");
        check(!RunEncounterRules.reconnectAllowed(at, at + 180_001L) && !RunEncounterRules.reconnectAllowed(at, at + 300_000L), "Reconnect accepted after the window");
        check(!RunEncounterRules.pauseExpired(0, at + 999_999L) && !RunEncounterRules.pauseExpired(at, at + 180_000L) && RunEncounterRules.pauseExpired(at, at + 180_001L), "Whole-run pause limit wrong");
        check(RunEncounterRules.lingering(at, at + 9_999L) && !RunEncounterRules.lingering(at, at + 10_000L), "10-second linger changed");
        check(RunEncounterRules.countsAsSurvivor(false, false, at, at + 9_999L) && !RunEncounterRules.countsAsSurvivor(false, false, at, at + 10_000L), "Survivor rule changed");

        // MDF-23: solo death pauses the run, a revive lifts it and the next wave may spawn; nobody left still closes at 180s.
        long dead = at;
        boolean soloDeadPresent = RunEncounterRules.countsAsSurvivor(true, true, 0, dead);
        long paused = RunEncounterRules.pausedAfterCheck(0, soloDeadPresent, dead);
        check(!soloDeadPresent && paused == dead, "Solo death did not pause the run");
        check(RunEncounterRules.pausedAfterCheck(paused, soloDeadPresent, dead + 60_000L) == dead, "Pause start moved while still dead");
        boolean revivedPresent = RunEncounterRules.countsAsSurvivor(true, false, 0, dead + 5_000L);
        check(RunEncounterRules.pausedAfterCheck(paused, revivedPresent, dead + 5_000L) == 0, "Revive did not lift the pause");
        check(!RunEncounterRules.pauseExpired(RunEncounterRules.pausedAfterCheck(paused, revivedPresent, dead + 5_000L), dead + 400_000L), "Revived run still expires from the old pause");
        long gone = RunEncounterRules.pausedAfterCheck(0, RunEncounterRules.countsAsSurvivor(false, false, at, at + 10_000L), at + 10_000L);
        check(gone == at + 10_000L && !RunEncounterRules.pauseExpired(gone, gone + 180_000L) && RunEncounterRules.pauseExpired(gone, gone + 180_001L), "All-members-gone 180s close changed");
        check(RunEncounterRules.pausedAfterCheck(gone, false, gone + 90_000L) == gone, "Pause restarted while nobody was present");
        String quiet = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        check(quiet.contains("RunEncounterRules.pausedAfterCheck(encounter.pausedAt,present,now)") && !quiet.contains("encounter==null||encounter.pausedAt!=0||encounter.failed)return;\n        boolean present"), "Runtime does not lift the pause through the rule");

        // The runtime uses the rules everywhere the old 5-minute limit lived and rejects removed members.
        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        check(!runtime.contains("300000"), "A 5-minute pause limit survived in the runtime");
        check(count(runtime, "RunEncounterRules.pauseExpired(") == 4, "Pause expiry is not shared by enter, reconnect and tick");
        check(count(runtime, "RunEncounterRules.reconnectAllowed(") >= 3, "Reconnect window is not enforced on every return path");
        check(count(runtime, "removedMember(") >= 3, "Removed members are not rejected on every entry path");
        check(runtime.contains("tickExits(server,now);"), "Reconnect expiry is not checked every tick");

        // The database gives the whole-run pause the same 180 seconds, with an inclusive boundary.
        FixtureDatabase.databaseName();
        FixtureMigrations.apply();
        var repo = new RunRepository();
        UUID account = UUID.randomUUID();
        UUID run = null;
        try {
            long generation = new AccountRepository().openSession(account).connectionGeneration();
            UUID character = new CharacterRepository().create(account, generation, 1, 1, "rw_" + account.toString().substring(0, 8)).character().characterId();
            new CharacterRepository().selectActive(account, generation, 2, character);
            sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?", character);
            run = repo.enter(account, generation, 10, RunDifficulty.FIRST_STEP, List.of(character)).runId();
            check(repo.pauseIfUnattended(character, List.of()) != null, "Unattended run did not pause");
            check(number("SELECT EXTRACT(EPOCH FROM pause_expires_at-paused_at)::bigint FROM dalmaru.run_instances WHERE run_id=? AND state='PAUSED'", run) == 180, "Database pause is not 180 seconds");
            check(repo.resume(character) != null && number("SELECT count(*) FROM dalmaru.run_instances WHERE run_id=? AND state='ACTIVE' AND pause_expires_at IS NULL", run) == 1, "Resume inside the window failed");
            check(repo.pauseIfUnattended(character, List.of()) != null, "Second pause failed");
            sql("UPDATE dalmaru.run_instances SET pause_expires_at=CURRENT_TIMESTAMP+INTERVAL '1 second' WHERE run_id=?", run);
            check(repo.expireTimedOut() >= 0 && number("SELECT count(*) FROM dalmaru.run_instances WHERE run_id=? AND state='PAUSED'", run) == 1, "Unexpired pause was finalized");
            sql("UPDATE dalmaru.run_instances SET pause_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE run_id=?", run);
            try { repo.resume(character); throw new AssertionError("Resume accepted after the window"); } catch (IllegalArgumentException expected) { }
            check(repo.expireTimedOut() >= 1 && number("SELECT count(*) FROM dalmaru.run_instances WHERE run_id=? AND state='FAILED'", run) == 1, "Expired pause was not finalized");
        } finally {
            if (run != null) sql("DELETE FROM dalmaru.run_instances WHERE run_id=?", run);
            sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?", account);
            EnhancementReplayCheck.cleanup(account);
        }
        System.out.println("Reconnect window 180s boundary, whole-run pause 180s (runtime and database), linger unchanged, removed-member rejection wired PASS");
    }

    private static int count(String text, String needle) {
        int total = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) total++;
        return total;
    }

    private static void sql(String text, Object... args) throws SQLException {
        try (var c = EnhancementReplayCheck.connection(); var q = c.prepareStatement(text)) { for (int i = 0; i < args.length; i++) q.setObject(i + 1, args[i]); q.executeUpdate(); }
    }

    private static long number(String text, Object... args) throws SQLException {
        try (var c = EnhancementReplayCheck.connection(); var q = c.prepareStatement(text)) {
            for (int i = 0; i < args.length; i++) q.setObject(i + 1, args[i]);
            try (var row = q.executeQuery()) { row.next(); return row.getLong(1); }
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
