package kr.dalmaru;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.party.PartyRunService;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunRepository;

/** Run entry opens the party roster in the same transaction; an invite then works, and a roster failure leaves no run. */
public final class RunEntryPartyOpenCheck {
    private RunEntryPartyOpenCheck() { }

    public static void main(String[] args) throws Exception {
        FixtureDatabase.databaseName();
        FixtureMigrations.apply();
        var repo = new RunRepository();
        List<UUID> accounts = new ArrayList<>(), runs = new ArrayList<>();
        String trap = "party_open_" + UUID.randomUUID().toString().replace("-", "");
        try {
            UUID leaderAccount = UUID.randomUUID(), recruitAccount = UUID.randomUUID(), failAccount = UUID.randomUUID();
            accounts.add(leaderAccount); accounts.add(recruitAccount); accounts.add(failAccount);
            long leaderGeneration = generation(leaderAccount), failGeneration = generation(failAccount);
            UUID leader = character(leaderAccount, leaderGeneration, "po_leader");
            UUID recruit = character(recruitAccount, generation(recruitAccount), "po_recruit");
            UUID failing = character(failAccount, failGeneration, "po_fail");

            UUID run = repo.enter(leaderAccount, leaderGeneration, 10, RunDifficulty.FIRST_STEP, List.of(leader)).runId();
            runs.add(run);
            check(count("SELECT count(*) FROM dalmaru.run_party_control WHERE run_id=? AND phase='READY' AND leader_character_id='" + leader + "'", run) == 1, "Roster not opened with entering leader");
            check(count("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id='" + leader + "' AND left_at IS NULL", run) == 1, "Leader lifecycle row missing");

            check(repo.enter(leaderAccount, leaderGeneration, 10, RunDifficulty.FIRST_STEP, List.of(leader)).replayed(), "Entry replay not recognised");
            check(count("SELECT count(*) FROM dalmaru.run_party_control WHERE run_id=?", run) == 1 && count("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=?", run) == 1, "Entry replay duplicated roster rows");

            try (var connection = EnhancementReplayCheck.connection()) {
                connection.setAutoCommit(false);
                var invited = PartyRunService.invite(connection, leaderAccount, leader, run, recruit, Instant.parse("2026-10-18T00:00:00Z"));
                check("INVITED".equals(invited.status()), "Invite after entry rejected: " + invited.reason());
                var accepted = PartyRunService.accept(connection, recruitAccount, recruit, run);
                check("ACCEPTED".equals(accepted.status()), "Accept after entry rejected: " + accepted.reason());
                connection.commit();
            }
            check(count("SELECT count(*) FROM dalmaru.run_participants WHERE run_id=?", run) == 2 && count("SELECT count(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND left_at IS NULL", run) == 2, "Accepted member not in roster");

            sql("CREATE FUNCTION dalmaru." + trap + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.leader_character_id='" + failing + "'::uuid THEN RAISE EXCEPTION 'fixture roster failure'; END IF; RETURN NEW; END $$");
            sql("CREATE TRIGGER " + trap + " BEFORE INSERT ON dalmaru.run_party_control FOR EACH ROW EXECUTE FUNCTION dalmaru." + trap + "()");
            try {
                repo.enter(failAccount, failGeneration, 20, RunDifficulty.FIRST_STEP, List.of(failing));
                throw new AssertionError("Entry succeeded without a roster");
            } catch (SQLException | IllegalArgumentException | IllegalStateException expected) { }
            check(count("SELECT count(*) FROM dalmaru.run_participants WHERE character_id=?", failing) == 0, "Roster failure left a run participant");
        } finally {
            drop(trap);
            for (UUID run : runs) sql("DELETE FROM dalmaru.run_instances WHERE run_id=?", run);
            for (UUID account : accounts) { sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?", account); EnhancementReplayCheck.cleanup(account); }
        }
        System.out.println("Run entry opens party roster in-transaction, invite/accept work, replay is single, roster failure rolls back PASS");
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
