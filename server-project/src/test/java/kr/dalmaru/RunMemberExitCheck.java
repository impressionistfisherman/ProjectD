package kr.dalmaru;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.run.RunContribution.Action;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunMechanicAccess.ExitReason;
import kr.dalmaru.run.RunMechanicAccess.ExitStatus;
import kr.dalmaru.run.RunMechanicAccess.MemberExit;
import kr.dalmaru.run.RunMemberExit;
import kr.dalmaru.run.RunMemberService;
import kr.dalmaru.run.RunReward;
import kr.dalmaru.run.RunRepository;

/**
 * P4-S07 database contract: one exit transaction cancels S12 eligibility, flags the return, hands the leader over and
 * bumps the roster revision. Repeats are idempotent, stale revisions and wrong phases change nothing, a failure rolls
 * everything back, and a removed member can neither finish the run nor be counted as an active participant.
 */
public final class RunMemberExitCheck {
    private RunMemberExitCheck() { }

    @FunctionalInterface private interface Operation { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        FixtureDatabase.databaseName();
        FixtureMigrations.apply();
        var repo = new RunRepository();
        var service = new RunMemberService();
        var adapter = new RunMemberExit();
        List<UUID> accounts = new ArrayList<>(), characters = new ArrayList<>(), runs = new ArrayList<>();
        List<Long> generations = new ArrayList<>();
        String trap = "member_exit_" + UUID.randomUUID().toString().replace("-", "");
        try {
            for (int i = 0; i < 4; i++) {
                UUID account = UUID.randomUUID();
                accounts.add(account);
                long generation = new AccountRepository().openSession(account).connectionGeneration();
                generations.add(generation);
                UUID character = new CharacterRepository().create(account, generation, 1, 1, "me_" + account.toString().substring(0, 8)).character().characterId();
                new CharacterRepository().selectActive(account, generation, 2, character);
                sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?", character);
                characters.add(character);
            }
            UUID a = characters.get(0), b = characters.get(1), c = characters.get(2), d = characters.get(3);
            UUID run = repo.enter(accounts.get(0), generations.get(0), 10, RunDifficulty.FIRST_STEP, characters).runId();
            runs.add(run);
            check(number("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND left_at IS NULL", run) == 4, "Entry did not open four roster rows");
            check(repo.activateParty(run), "Roster activation failed");
            repo.recordContribution(run, b, Action.EFFECT);
            repo.recordContribution(run, d, Action.EFFECT);
            repo.saveReturn(run, b, "minecraft:overworld", 1, 64, 2);
            repo.saveReturn(run, d, "minecraft:overworld", 3, 64, 4);
            long rev0 = number("SELECT roster_revision FROM dalmaru.run_party_control WHERE run_id=?", run);

            // Voluntary exit of a non-leader member.
            MemberExit voluntary = service.exit(run, accounts.get(1), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION);
            check(voluntary.status() == ExitStatus.REMOVED && voluntary.characterId().equals(b) && voluntary.revision() == rev0 + 1 && voluntary.returnPending(), "Voluntary exit result wrong: " + voluntary);
            check(number("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id='" + b + "' AND leave_reason='VOLUNTARY' AND left_at IS NOT NULL AND eligibility_revoked AND return_pending", run) == 1, "Lifecycle not updated atomically");
            check(number("SELECT count(*) FROM dalmaru.run_contributions WHERE run_id=? AND character_id='" + b + "' AND revoked", run) == 1, "S12 eligibility not cancelled");
            check(number("SELECT count(*) FROM dalmaru.run_participants WHERE run_id=? AND character_id='" + b + "' AND return_pending", run) == 1, "Return not pending");
            check(number("SELECT count(*) FROM dalmaru.run_party_control WHERE run_id=? AND leader_character_id='" + a + "' AND roster_revision=" + (rev0 + 1), run) == 1, "Leader or revision wrong after member exit");
            check(repo.active(b) == null, "Removed member still resolves an active run");
            check(repo.active(a) != null && repo.pendingReturn(a) == null, "Remaining member disturbed");
            var pending = repo.pendingReturn(b);
            check(pending != null && pending.runId().equals(run), "Removed member has no safe return while the run is active");

            // A repeat is idempotent and reports the unchanged revision.
            MemberExit repeat = service.exit(run, accounts.get(1), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION);
            check(repeat.status() == ExitStatus.ALREADY_REMOVED && repeat.revision() == rev0 + 1 && repeat.returnPending(), "Repeat exit not idempotent: " + repeat);
            check(number("SELECT roster_revision FROM dalmaru.run_party_control WHERE run_id=?", run) == rev0 + 1, "Repeat exit changed the revision");

            // Return acknowledgement is server trusted, applies once and clears both flags.
            check(repo.acknowledgeExitReturn(run, b) && !repo.acknowledgeExitReturn(run, b), "Exit return acknowledgement not exactly-once");
            check(repo.pendingReturn(b) == null && number("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id='" + b + "' AND NOT return_pending", run) == 1, "Acknowledged return still pending");
            check(!repo.acknowledgeExitReturn(run, a), "Acknowledged a member who never left");

            // A removed member is free to enter another run while the first one continues.
            UUID second = repo.enter(accounts.get(1), generations.get(1), 50, RunDifficulty.FIRST_STEP, List.of(b)).runId();
            runs.add(second);
            reject(() -> repo.finish(accounts.get(1), generations.get(1), 51, run, false));

            // Stale revision changes nothing; the exact revision then removes through the shared adapter (kick caller).
            MemberExit stale = service.exit(run, accounts.get(2), ExitReason.VOLUNTARY, rev0);
            check(stale.status() == ExitStatus.STALE_REVISION && stale.revision() == rev0 + 1, "Stale revision accepted: " + stale);
            check(number("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id='" + c + "' AND left_at IS NULL AND NOT eligibility_revoked", run) == 1, "Stale exit changed the member");
            MemberExit kicked;
            try (var connection = EnhancementReplayCheck.connection()) {
                connection.setAutoCommit(false);
                try (var lock = connection.prepareStatement("SELECT account_id FROM dalmaru.accounts WHERE account_id=? FOR UPDATE")) { lock.setObject(1, accounts.get(0)); lock.executeQuery().close(); }
                kicked = adapter.removeMember(connection, run, d, rev0 + 1, ExitReason.KICKED);
                connection.commit();
            }
            check(kicked.status() == ExitStatus.REMOVED && kicked.revision() == rev0 + 2 && kicked.returnPending(), "Kick through the adapter failed: " + kicked);
            check(number("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id='" + d + "' AND leave_reason='KICKED' AND eligibility_revoked", run) == 1, "Kick reason not recorded");
            check(repo.pendingReturn(d) != null && repo.acknowledgeReturn(accounts.get(3), generations.get(3), d, run) && repo.pendingReturn(d) == null, "Login return path did not clear a removed member");
            check(number("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id='" + d + "' AND NOT return_pending", run) == 1, "Login acknowledgement left the lifecycle flag set");

            // Unknown members and accounts are rejected without side effects.
            check(service.exit(run, UUID.randomUUID(), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION).status() == ExitStatus.NOT_PARTICIPANT, "Unknown account removed");
            try (var connection = EnhancementReplayCheck.connection()) {
                connection.setAutoCommit(false);
                check(adapter.removeMember(connection, run, UUID.randomUUID(), RunMemberExit.ANY_REVISION, ExitReason.KICKED).status() == ExitStatus.NOT_PARTICIPANT, "Unknown character removed");
                check(adapter.removeMember(connection, UUID.randomUUID(), c, RunMemberExit.ANY_REVISION, ExitReason.KICKED).status() == ExitStatus.WRONG_PHASE, "Unknown run not rejected");
                connection.rollback();
            }

            // Leader exit hands over to the next remaining member; the last member ends the run.
            MemberExit leader = service.exit(run, accounts.get(0), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION);
            check(leader.status() == ExitStatus.REMOVED && !leader.returnPending(), "Leader exit failed: " + leader);
            check(number("SELECT count(*) FROM dalmaru.run_party_control WHERE run_id=? AND leader_character_id='" + c + "'", run) == 1 && number("SELECT count(*) FROM dalmaru.run_instances WHERE run_id=? AND state='ACTIVE'", run) == 1, "Leader hand-over or run state wrong");
            MemberExit last = service.exit(run, accounts.get(2), ExitReason.RECONNECT_EXPIRED, RunMemberExit.ANY_REVISION);
            check(last.status() == ExitStatus.REMOVED && !last.returnPending(), "Last member exit failed: " + last);
            check(number("SELECT count(*) FROM dalmaru.run_instances WHERE run_id=? AND state='FAILED' AND finished_at IS NOT NULL", run) == 1, "Run survived its last member");
            check(service.exit(run, accounts.get(2), ExitReason.RECONNECT_EXPIRED, RunMemberExit.ANY_REVISION).status() == ExitStatus.ALREADY_REMOVED, "Late repeat after run end not idempotent");

            // A member who has not left cannot be removed from an ended run.
            sql("UPDATE dalmaru.run_instances SET state='FAILED', finished_at=CURRENT_TIMESTAMP WHERE run_id=?", second);
            check(service.exit(second, accounts.get(1), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION).status() == ExitStatus.WRONG_PHASE, "Exit accepted for an ended run");

            // S12: a member who left earns neither the clear nor a reward; the remaining member does.
            UUID third = repo.enter(accounts.get(0), generations.get(0), 60, RunDifficulty.FIRST_STEP, List.of(a, c)).runId();
            runs.add(third);
            repo.recordContribution(third, a, Action.EFFECT);
            repo.recordContribution(third, c, Action.EFFECT);
            long clearsA = number("SELECT COALESCE(SUM(clear_count),0) FROM dalmaru.character_run_weekly_clears WHERE character_id=?", a);
            long clearsC = number("SELECT COALESCE(SUM(clear_count),0) FROM dalmaru.character_run_weekly_clears WHERE character_id=?", c);
            check(service.exit(third, accounts.get(2), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION).status() == ExitStatus.REMOVED, "Member exit before finish failed");
            reject(() -> repo.finish(accounts.get(2), generations.get(2), 61, third, true, Map.of(c, Action.EFFECT)));
            repo.finish(accounts.get(0), generations.get(0), 62, third, true, Map.of(a, Action.EFFECT, c, Action.EFFECT));
            check(repo.rewards(third).stream().map(RunReward::characterId).collect(java.util.stream.Collectors.toSet()).equals(Set.of(a)), "A removed member received a reward");
            check(number("SELECT COALESCE(SUM(clear_count),0) FROM dalmaru.character_run_weekly_clears WHERE character_id=?", a) == clearsA + 1 && number("SELECT COALESCE(SUM(clear_count),0) FROM dalmaru.character_run_weekly_clears WHERE character_id=?", c) == clearsC, "Weekly clear granted to the wrong member");

            // A failure inside the transaction leaves no partial removal; a retry then succeeds.
            UUID fourth = repo.enter(accounts.get(1), generations.get(1), 70, RunDifficulty.FIRST_STEP, List.of(b, d)).runId();
            runs.add(fourth);
            repo.recordContribution(fourth, d, Action.EFFECT);
            repo.saveReturn(fourth, d, "minecraft:overworld", 5, 64, 6);
            sql("CREATE FUNCTION dalmaru." + trap + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.run_id='" + fourth + "'::uuid THEN RAISE EXCEPTION 'fixture exit failure'; END IF; RETURN NEW; END $$");
            sql("CREATE TRIGGER " + trap + " BEFORE UPDATE ON dalmaru.run_party_control FOR EACH ROW EXECUTE FUNCTION dalmaru." + trap + "()");
            try { service.exit(fourth, accounts.get(3), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION); throw new AssertionError("Exit succeeded despite failure"); }
            catch (SQLException expected) { }
            check(number("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id='" + d + "' AND left_at IS NULL AND NOT eligibility_revoked", fourth) == 1
                    && number("SELECT count(*) FROM dalmaru.run_contributions WHERE run_id=? AND character_id='" + d + "' AND NOT revoked", fourth) == 1, "Failed exit left a partial removal");
            drop(trap);
            check(service.exit(fourth, accounts.get(3), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION).status() == ExitStatus.REMOVED, "Retry after failure did not remove");
            System.out.println("Member exit atomic eligibility/return/leader/revision, idempotent repeat, stale/phase/unknown rejection, ack once, re-entry, S12 reward isolation, rollback and retry PASS");
        } finally {
            drop(trap);
            for (UUID run : runs) sql("DELETE FROM dalmaru.run_instances WHERE run_id=?", run);
            for (UUID account : accounts) { sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?", account); EnhancementReplayCheck.cleanup(account); }
        }
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

    private static void drop(String trap) throws SQLException {
        sql("DROP TRIGGER IF EXISTS " + trap + " ON dalmaru.run_party_control");
        sql("DROP FUNCTION IF EXISTS dalmaru." + trap + "()");
    }

    private static void reject(Operation operation) {
        try { operation.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Operation was accepted");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
