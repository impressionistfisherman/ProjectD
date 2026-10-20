package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.party.PartyRunService;
import kr.dalmaru.run.PartyActivator;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunRepository;

/**
 * Run entry opens the party roster in the same transaction and invites work; the first combat stage (GATEKEEPER)
 * closes it. Activation is idempotent, rolls back on failure and is retried.
 */
public final class RunEntryPartyOpenCheck {
    private RunEntryPartyOpenCheck() { }

    public static void main(String[] args) throws Exception {
        var beforeEntry = PartyRunService.invite(null, UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), Instant.EPOCH);
        check("REJECT".equals(beforeEntry.status()) && "거절: 입장 후 초대할 수 있습니다.".equals(beforeEntry.reason()), beforeEntry.reason());
        var beforeAccept = PartyRunService.accept(null, UUID.randomUUID(), UUID.randomUUID(), null);
        check("REJECT".equals(beforeAccept.status()) && "거절: 입장 후 초대할 수 있습니다.".equals(beforeAccept.reason()), beforeAccept.reason());
        var beforeRoster = PartyRunService.roster(null, null);
        check("REJECT".equals(beforeRoster.status()) && "거절: 입장 후 초대할 수 있습니다.".equals(beforeRoster.reason()), beforeRoster.reason());
        FixtureDatabase.databaseName();
        FixtureMigrations.apply();
        var repo = new RunRepository();
        List<UUID> accounts = new ArrayList<>(), runs = new ArrayList<>();
        String insertTrap = "party_open_" + UUID.randomUUID().toString().replace("-", "");
        String updateTrap = "party_activate_" + UUID.randomUUID().toString().replace("-", "");
        try {
            UUID leaderAccount = UUID.randomUUID(), recruitAccount = UUID.randomUUID(), lateAccount = UUID.randomUUID(), failAccount = UUID.randomUUID(), retryAccount = UUID.randomUUID();
            for (UUID account : List.of(leaderAccount, recruitAccount, lateAccount, failAccount, retryAccount)) accounts.add(account);
            long leaderGeneration = generation(leaderAccount), failGeneration = generation(failAccount), retryGeneration = generation(retryAccount);
            UUID leader = character(leaderAccount, leaderGeneration, "po_leader");
            UUID recruit = character(recruitAccount, generation(recruitAccount), "po_recruit");
            UUID late = character(lateAccount, generation(lateAccount), "po_late");
            UUID failing = character(failAccount, failGeneration, "po_fail");
            UUID retrying = character(retryAccount, retryGeneration, "po_retry");

            UUID run = repo.enter(leaderAccount, leaderGeneration, 10, RunDifficulty.FIRST_STEP, List.of(leader)).runId();
            runs.add(run);
            UUID retryRun = repo.enter(retryAccount, retryGeneration, 11, RunDifficulty.FIRST_STEP, List.of(retrying)).runId();
            runs.add(retryRun);
            check(count("SELECT count(*) FROM dalmaru.run_party_control WHERE run_id=? AND phase='READY' AND leader_character_id='" + leader + "'", run) == 1, "Roster not opened with entering leader");
            check(count("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id='" + leader + "' AND left_at IS NULL", run) == 1, "Leader lifecycle row missing");

            check(repo.enter(leaderAccount, leaderGeneration, 10, RunDifficulty.FIRST_STEP, List.of(leader)).replayed(), "Entry replay not recognised");
            check(count("SELECT count(*) FROM dalmaru.run_party_control WHERE run_id=?", run) == 1 && count("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=?", run) == 1, "Entry replay duplicated roster rows");

            try (var connection = EnhancementReplayCheck.connection()) {
                connection.setAutoCommit(false);
                var invited = PartyRunService.invite(connection, leaderAccount, leader, run, recruit, Instant.parse("2026-10-18T00:00:00Z"));
                check("INVITED".equals(invited.status()), "Invite after entry rejected: " + invited.reason());
                var pending = PartyRunService.invite(connection, leaderAccount, leader, run, late, Instant.parse("2026-10-18T00:00:01Z"));
                check("INVITED".equals(pending.status()), "Second invite after entry rejected: " + pending.reason());
                var accepted = PartyRunService.accept(connection, recruitAccount, recruit, run);
                check("ACCEPTED".equals(accepted.status()), "Accept after entry rejected: " + accepted.reason());
                connection.commit();
            }
            check(count("SELECT count(*) FROM dalmaru.run_participants WHERE run_id=?", run) == 2 && count("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND left_at IS NULL", run) == 2, "Accepted member not in roster");

            // GATEKEEPER starts: roster activates once, pending invites are dropped, later invites and accepts are refused.
            check(repo.activateParty(run), "Activation failed");
            long revision = count("SELECT roster_revision FROM dalmaru.run_party_control WHERE run_id=? AND phase='ACTIVE'", run);
            check(revision > 0, "Roster did not become ACTIVE");
            check(repo.activateParty(run) && count("SELECT roster_revision FROM dalmaru.run_party_control WHERE run_id=? AND phase='ACTIVE'", run) == revision, "Repeat activation changed the roster");
            check(count("SELECT count(*) FROM dalmaru.run_party_invites WHERE run_id=?", run) == 0, "Pending invite survived activation");
            try (var connection = EnhancementReplayCheck.connection()) {
                connection.setAutoCommit(false);
                var refused = PartyRunService.invite(connection, leaderAccount, leader, run, late, Instant.parse("2026-10-18T00:01:00Z"));
                check("REJECT".equals(refused.status()), "Invite after GATEKEEPER start accepted");
                var lateAccept = PartyRunService.accept(connection, lateAccount, late, run);
                check("REJECT".equals(lateAccept.status()), "Accept after GATEKEEPER start accepted");
                connection.commit();
            }
            check(count("SELECT count(*) FROM dalmaru.run_participants WHERE run_id=?", run) == 2, "Late member joined after GATEKEEPER start");
            check(repo.activateParty(UUID.randomUUID()), "Run without roster must be a no-op");

            // Activation failure rolls back and a retry succeeds.
            sql("CREATE FUNCTION dalmaru." + updateTrap + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.run_id='" + retryRun + "'::uuid AND NEW.phase='ACTIVE' THEN RAISE EXCEPTION 'fixture activation failure'; END IF; RETURN NEW; END $$");
            sql("CREATE TRIGGER " + updateTrap + " BEFORE UPDATE ON dalmaru.run_party_control FOR EACH ROW EXECUTE FUNCTION dalmaru." + updateTrap + "()");
            try { repo.activateParty(retryRun); throw new AssertionError("Activation succeeded despite failure"); }
            catch (SQLException | IllegalStateException expected) { }
            check(count("SELECT count(*) FROM dalmaru.run_party_control WHERE run_id=? AND phase='READY'", retryRun) == 1, "Failed activation left a partial roster change");
            sql("DROP TRIGGER IF EXISTS " + updateTrap + " ON dalmaru.run_party_control");
            check(repo.activateParty(retryRun) && count("SELECT count(*) FROM dalmaru.run_party_control WHERE run_id=? AND phase='ACTIVE'", retryRun) == 1, "Retry after failure did not activate");

            // The activator retries transient failures and runs a duplicate request only once.
            var attempts = new AtomicInteger();
            var activator = new PartyActivator(id -> { if (attempts.incrementAndGet() < 3) throw new SQLException("transient"); });
            UUID probe = UUID.randomUUID();
            activator.request(probe); activator.request(probe);
            long deadline = System.currentTimeMillis() + 5000;
            while (!activator.done(probe) && System.currentTimeMillis() < deadline) Thread.sleep(20);
            check(activator.done(probe) && attempts.get() == 3, "Activator did not retry to success exactly once");
            activator.request(probe); Thread.sleep(100);
            check(attempts.get() == 3, "Activator repeated a finished activation");

            // The runtime asks for activation exactly where the gatekeeper group first spawns.
            String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
            check(runtime.contains("if(encounter.group==0){partyActivator.request(encounter.runId);spawnGroup(encounter,1);return;}"), "GATEKEEPER start does not request activation");
            check(runtime.contains("partyActivator.forget(runId)"), "Close does not forget activation");

            // Roster failure at entry leaves no run.
            sql("CREATE FUNCTION dalmaru." + insertTrap + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.leader_character_id='" + failing + "'::uuid THEN RAISE EXCEPTION 'fixture roster failure'; END IF; RETURN NEW; END $$");
            sql("CREATE TRIGGER " + insertTrap + " BEFORE INSERT ON dalmaru.run_party_control FOR EACH ROW EXECUTE FUNCTION dalmaru." + insertTrap + "()");
            try {
                repo.enter(failAccount, failGeneration, 20, RunDifficulty.FIRST_STEP, List.of(failing));
                throw new AssertionError("Entry succeeded without a roster");
            } catch (SQLException | IllegalArgumentException | IllegalStateException expected) { }
            check(count("SELECT count(*) FROM dalmaru.run_participants WHERE character_id=?", failing) == 0, "Roster failure left a run participant");
        } finally {
            drop(insertTrap);
            drop(updateTrap);
            for (UUID run : runs) sql("DELETE FROM dalmaru.run_instances WHERE run_id=?", run);
            for (UUID account : accounts) { sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?", account); EnhancementReplayCheck.cleanup(account); }
        }
        System.out.println("Roster opens at run entry, invites work until GATEKEEPER start then refuse, pre-entry invite explains to enter first, activation idempotent/rollback/retry, entry roster failure rolls back PASS");
    }

    private static long generation(UUID account) throws Exception { return new AccountRepository().openSession(account).connectionGeneration(); }

    private static UUID character(UUID account, long generation, String prefix) throws Exception {
        UUID id = new CharacterRepository().create(account, generation, 1, 1, prefix + "_" + account.toString().substring(0, 6)).character().characterId();
        new CharacterRepository().selectActive(account, generation, 2, id);
        sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?", id);
        return id;
    }

    private static void sql(String text, Object... args) throws SQLException {
        try (var c = EnhancementReplayCheck.connection(); var q = c.prepareStatement(text)) { for (int i = 0; i < args.length; i++) q.setObject(i + 1, args[i]); q.executeUpdate(); }
    }

    private static long count(String text, UUID id) throws SQLException {
        try (var c = EnhancementReplayCheck.connection(); var q = c.prepareStatement(text)) { q.setObject(1, id); try (var row = q.executeQuery()) { row.next(); return row.getLong(1); } }
    }

    private static void drop(String trap) throws SQLException {
        sql("DROP TRIGGER IF EXISTS " + trap + " ON dalmaru.run_party_control");
        sql("DROP FUNCTION IF EXISTS dalmaru." + trap + "()");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
