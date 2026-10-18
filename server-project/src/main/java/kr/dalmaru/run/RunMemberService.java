package kr.dalmaru.run;

import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.run.RunMechanicAccess.ExitReason;
import kr.dalmaru.run.RunMechanicAccess.ExitStatus;
import kr.dalmaru.run.RunMechanicAccess.MemberExit;

/**
 * Transaction owner for server-initiated exits: own leave and reconnect expiry. Lock order is account, run control,
 * run, participant (the last three inside {@link RunMemberExit}). The kick vote opens its own transaction and calls
 * the same adapter.
 */
public final class RunMemberService {
    private final RunRepository repository;
    private final RunMechanicAccess.MemberExitAdapter adapter;

    public RunMemberService() { this(new RunRepository(), new RunMemberExit()); }

    public RunMemberService(RunRepository repository, RunMechanicAccess.MemberExitAdapter adapter) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.adapter = java.util.Objects.requireNonNull(adapter);
    }

    /** The account's participant in the run is resolved on the server; clients never name a character. */
    public MemberExit exit(UUID runId, UUID accountId, ExitReason reason, long expectedRevision) throws SQLException {
        if (reason == ExitReason.KICKED) throw new IllegalArgumentException("A kick is applied by the vote transaction.");
        try (var connection = repository.connect()) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("SELECT account_id FROM dalmaru.accounts WHERE account_id=? FOR UPDATE")) {
                    lock.setObject(1, accountId);
                    lock.executeQuery().close();
                }
                UUID character = null;
                try (var query = connection.prepareStatement("SELECT p.character_id FROM dalmaru.run_participants p JOIN dalmaru.characters c ON c.character_id=p.character_id WHERE p.run_id=? AND c.account_id=?")) {
                    query.setObject(1, runId);
                    query.setObject(2, accountId);
                    try (var row = query.executeQuery()) { if (row.next()) character = row.getObject(1, UUID.class); }
                }
                if (character == null) {
                    connection.commit();
                    return new MemberExit(ExitStatus.NOT_PARTICIPANT, runId, null, 0, false);
                }
                MemberExit result = adapter.removeMember(connection, runId, character, expectedRevision, reason);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    /** Clears the pending return after the runtime has put an online member back; offline members use the login path. */
    public boolean acknowledgeReturn(UUID runId, UUID characterId) throws SQLException {
        return repository.acknowledgeExitReturn(runId, characterId);
    }
}
