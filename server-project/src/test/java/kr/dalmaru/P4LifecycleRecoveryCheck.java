package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunMechanicAccess.ExitReason;
import kr.dalmaru.run.RunMechanicAccess.ExitStatus;
import kr.dalmaru.run.RunMemberExit;
import kr.dalmaru.run.RunMemberService;
import kr.dalmaru.run.RunRepository;

/**
 * P4-S10 lifecycle recovery: after pause expiry, the run deadline or a server restart every member still gets exactly
 * one safe return (including members who left earlier and were not yet returned), nobody is returned twice, nobody
 * stays blocked from a new run, and the runtime releases entities and terrain on every end path.
 */
public final class P4LifecycleRecoveryCheck {
    private P4LifecycleRecoveryCheck() { }

    @FunctionalInterface private interface Operation { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        // Runtime end paths release players, monsters and terrain in a fixed, failure-tolerant order.
        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        String close = method(runtime, "public void close(MinecraftServer server,UUID runId) {");
        int begin = close.indexOf("lifecycle.beginClose()"), failed = close.indexOf("encounter.failed=true"), forget = close.indexOf("partyActivator.forget(runId)");
        int teleport = close.indexOf("player.teleportTo(state.returnLevel"), released = close.indexOf("releaseDisconnected.accept"), removed = close.indexOf("playerRuns.remove(entry.getKey())");
        int mobs = close.indexOf("mob.discard()"), entities = close.indexOf("entity.discard()"), terrain = close.indexOf("BeaconTowerMap.restore("), journal = close.indexOf("RunTerrainRecovery.complete(");
        int unload = close.indexOf("keepRegionLoaded(encounter,false)"), forgetRun = close.indexOf("encounters.remove(runId)"), closed = close.indexOf("lifecycle.closed()");
        check(begin >= 0 && begin < failed && failed < forget && forget < teleport && teleport < released, "Close does not stop the run before returning players");
        check(removed > released && close.contains("finally{playerRuns.remove(entry.getKey());}"), "A player state can survive a failed return");
        check(mobs > removed && mobs < entities && entities < terrain && terrain < journal && journal < unload && unload < forgetRun && forgetRun < closed, "Close order for monsters, region entities, terrain and journal changed");
        check(close.contains("finally{try{keepRegionLoaded(encounter,false);}finally{encounters.remove(runId);}}") && close.contains("lifecycle.closed();"), "Region unload or lifecycle close is not in a finally block");
        check(method(runtime, "private void publishExit(").contains("noneMatch(member->member.runId.equals(exit.runId())))close(server,exit.runId())"), "The last member leaving does not close the runtime");
        check(method(runtime, "public void tick(").contains("kr.dalmaru.run.RunEncounterRules.pauseExpired(encounter.pausedAt,now))){close(server,encounter.runId);continue;}") && method(runtime, "public void tick(").contains("now>=encounter.deadline"), "Deadline or pause expiry does not close the runtime");
        String networking = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        check(networking.contains("RunTerrainRecovery.recover(server);RUNS.cancelUnfinished();") && networking.contains("RUN_RUNTIME.closeAll(server)") && networking.contains("RUNS.expireTimedOut()") && networking.contains("RUNS.pendingReturn(session.activeCharacterId())"), "Startup recovery, shutdown close, expiry sweep or login return is not wired");

        // Database recovery.
        FixtureDatabase.databaseName();
        FixtureMigrations.apply();
        var repo = new RunRepository();
        var service = new RunMemberService();
        List<UUID> accounts = new ArrayList<>(), characters = new ArrayList<>(), runs = new ArrayList<>();
        List<Long> generations = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                UUID account = UUID.randomUUID();
                accounts.add(account);
                long generation = new AccountRepository().openSession(account).connectionGeneration();
                generations.add(generation);
                UUID character = new CharacterRepository().create(account, generation, 1, 1, "lr_" + account.toString().substring(0, 8)).character().characterId();
                new CharacterRepository().selectActive(account, generation, 2, character);
                sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?", character);
                characters.add(character);
            }
            UUID a = characters.get(0), b = characters.get(1), c = characters.get(2);

            // Pause expiry: a member who already left was returned once and is not returned again; the others are.
            UUID first = repo.enter(accounts.get(0), generations.get(0), 10, RunDifficulty.FIRST_STEP, characters).runId();
            runs.add(first);
            repo.activateParty(first);
            for (UUID member : characters) repo.saveReturn(first, member, "minecraft:overworld", 1, 64, 2);
            check(service.exit(first, accounts.get(1), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION).status() == ExitStatus.REMOVED, "Exit before expiry failed");
            check(repo.acknowledgeExitReturn(first, b), "Runtime return of the leaver was not acknowledged");
            check(repo.pauseIfUnattended(a, List.of()) != null, "Run did not pause");
            sql("UPDATE dalmaru.run_instances SET pause_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE run_id=?", first);
            reject(() -> repo.resume(a));
            check(repo.expireTimedOut() >= 1 && number("SELECT count(*) FROM dalmaru.run_instances WHERE run_id=? AND state='FAILED'", first) == 1, "Expired pause was not finalized");
            check(repo.active(a) == null && repo.active(c) == null && repo.active(b) == null, "A finished run still resolves as active");
            check(repo.pendingReturn(a) != null && repo.pendingReturn(c) != null, "A member who stayed lost the safe return");
            check(repo.pendingReturn(b) == null, "A member who already returned would be returned twice");
            check(repo.acknowledgeReturn(accounts.get(0), generations.get(0), a, first) && !repo.acknowledgeReturn(accounts.get(0), generations.get(0), a, first), "Return is not exactly-once for a member who stayed");
            check(repo.acknowledgeReturn(accounts.get(2), generations.get(2), c, first) && repo.pendingReturn(c) == null, "Second member return failed");
            check(number("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND return_pending", first) == 0, "Lifecycle return flags left set");

            // Restart: a leaver who was never returned (offline) still gets the one safe return afterwards.
            UUID second = repo.enter(accounts.get(0), generations.get(0), 20, RunDifficulty.FIRST_STEP, characters).runId();
            runs.add(second);
            repo.activateParty(second);
            repo.saveReturn(second, a, "minecraft:overworld", 7, 64, 8);
            repo.saveReturn(second, b, "minecraft:overworld", 9, 64, 10);
            check(service.exit(second, accounts.get(1), ExitReason.RECONNECT_EXPIRED, RunMemberExit.ANY_REVISION).status() == ExitStatus.REMOVED, "Expiry exit failed");
            repo.cancelUnfinished();
            check(number("SELECT count(*) FROM dalmaru.run_instances WHERE run_id=? AND state='FAILED'", second) == 1, "Restart did not fail the run");
            check(repo.pendingReturn(a) != null && repo.pendingReturn(b) != null && repo.pendingReturn(c) == null, "Restart return set is wrong");
            check(repo.pendingReturn(b).x() == 9 && repo.pendingReturn(a).x() == 7, "Return position was not preserved");
            check(repo.acknowledgeReturn(accounts.get(1), generations.get(1), b, second) && repo.pendingReturn(b) == null, "Offline leaver's return was not confirmed once");
            check(repo.acknowledgeReturn(accounts.get(0), generations.get(0), a, second), "Stayer's return was not confirmed");

            // The deadline ends a run the same way and nobody stays blocked from a new run.
            UUID third = repo.enter(accounts.get(0), generations.get(0), 30, RunDifficulty.FIRST_STEP, List.of(a)).runId();
            runs.add(third);
            sql("UPDATE dalmaru.run_instances SET deadline_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE run_id=?", third);
            check(repo.expireTimedOut() >= 1 && number("SELECT count(*) FROM dalmaru.run_instances WHERE run_id=? AND state='FAILED'", third) == 1 && repo.active(a) == null, "Deadline did not finish the run");
            UUID fourth = repo.enter(accounts.get(1), generations.get(1), 40, RunDifficulty.FIRST_STEP, List.of(b)).runId();
            runs.add(fourth);
            check(number("SELECT count(*) FROM dalmaru.run_instances WHERE run_id=? AND state='ACTIVE'", fourth) == 1, "A returned member is blocked from a new run");
            System.out.println("Lifecycle recovery: close order for players/monsters/terrain/journal, last-leaver close, pause/deadline/restart finish, exactly-once safe return incl. earlier leavers, no re-block PASS (runtime source contract; in-world run not performed)");
        } finally {
            for (UUID run : runs) sql("DELETE FROM dalmaru.run_instances WHERE run_id=?", run);
            for (UUID account : accounts) { sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?", account); EnhancementReplayCheck.cleanup(account); }
        }
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Missing " + signature);
        int open = source.indexOf('{', start + signature.length() - 1), depth = 1, end = open + 1;
        for (; depth > 0; end++) { char value = source.charAt(end); if (value == '{') depth++; else if (value == '}') depth--; }
        return source.substring(start, end);
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

    private static void reject(Operation operation) {
        try { operation.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Operation was accepted");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
