package kr.dalmaru.run;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import kr.dalmaru.run.RunMechanicAccess.ExitReason;
import kr.dalmaru.run.RunMechanicAccess.ExitStatus;
import kr.dalmaru.run.RunMechanicAccess.MemberExit;

/**
 * Database half of an individual run exit (voluntary, kicked, reconnect expired).
 *
 * <p>The caller owns the transaction and has already taken the account and request locks, in that order. This class
 * takes the run control row first, then the run row, then the participant and lifecycle rows, so every exit path
 * (S07 voluntary/expiry and the S08 kick vote) uses one lock order. Eligibility revocation (S12), the pending return
 * flag, leader hand-over and the roster revision change in the same transaction; nothing is partially removed.
 * Runtime removal is published by the caller only after commit.
 */
public final class RunMemberExit implements RunMechanicAccess.MemberExitAdapter {
    /** Skips the revision check. Only server-initiated exits (own member leaving, reconnect expiry) may use it. */
    public static final long ANY_REVISION = -1L;

    @Override
    public MemberExit removeMember(Connection connection, UUID runId, UUID characterId, long expectedRevision, ExitReason reason) throws SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(characterId);
        Objects.requireNonNull(reason);
        long revision;
        UUID leader;
        try (var query = connection.prepareStatement("SELECT roster_revision, leader_character_id FROM dalmaru.run_party_control WHERE run_id=? FOR UPDATE")) {
            query.setObject(1, runId);
            try (var row = query.executeQuery()) {
                if (!row.next()) return new MemberExit(ExitStatus.WRONG_PHASE, runId, characterId, 0, false);
                revision = row.getLong(1);
                leader = row.getObject(2, UUID.class);
            }
        }
        String state;
        try (var query = connection.prepareStatement("SELECT state FROM dalmaru.run_instances WHERE run_id=? FOR UPDATE")) {
            query.setObject(1, runId);
            try (var row = query.executeQuery()) {
                if (!row.next()) return new MemberExit(ExitStatus.WRONG_PHASE, runId, characterId, revision, false);
                state = row.getString(1);
            }
        }
        boolean checkpoint;
        try (var query = connection.prepareStatement("SELECT return_dimension IS NOT NULL FROM dalmaru.run_participants WHERE run_id=? AND character_id=? FOR UPDATE")) {
            query.setObject(1, runId);
            query.setObject(2, characterId);
            try (var row = query.executeQuery()) {
                if (!row.next()) return new MemberExit(ExitStatus.NOT_PARTICIPANT, runId, characterId, revision, false);
                checkpoint = row.getBoolean(1);
            }
        }
        boolean left;
        try (var query = connection.prepareStatement("SELECT left_at IS NOT NULL FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id=? FOR UPDATE")) {
            query.setObject(1, runId);
            query.setObject(2, characterId);
            try (var row = query.executeQuery()) {
                if (!row.next()) return new MemberExit(ExitStatus.NOT_PARTICIPANT, runId, characterId, revision, false);
                left = row.getBoolean(1);
            }
        }
        if (left) return new MemberExit(ExitStatus.ALREADY_REMOVED, runId, characterId, revision, checkpoint);
        if (!"ACTIVE".equals(state) && !"PAUSED".equals(state)) return new MemberExit(ExitStatus.WRONG_PHASE, runId, characterId, revision, false);
        if (expectedRevision != ANY_REVISION && expectedRevision != revision) return new MemberExit(ExitStatus.STALE_REVISION, runId, characterId, revision, false);

        try (var update = connection.prepareStatement("UPDATE dalmaru.run_member_lifecycle SET leave_reason=?, left_at=CURRENT_TIMESTAMP, eligibility_revoked=TRUE, return_pending=? WHERE run_id=? AND character_id=? AND left_at IS NULL")) {
            update.setString(1, reason.name());
            update.setBoolean(2, checkpoint);
            update.setObject(3, runId);
            update.setObject(4, characterId);
            if (update.executeUpdate() != 1) throw new SQLException("Member exit lost its lifecycle row.");
        }
        RunRepository.writeContribution(connection, runId, characterId, null, true);
        if (checkpoint) {
            try (var update = connection.prepareStatement("UPDATE dalmaru.run_participants SET return_pending=TRUE WHERE run_id=? AND character_id=?")) {
                update.setObject(1, runId);
                update.setObject(2, characterId);
                update.executeUpdate();
            }
        }
        UUID successor = null;
        try (var query = connection.prepareStatement("SELECT character_id FROM dalmaru.run_member_lifecycle WHERE run_id=? AND left_at IS NULL ORDER BY character_id LIMIT 1")) {
            query.setObject(1, runId);
            try (var row = query.executeQuery()) { if (row.next()) successor = row.getObject(1, UUID.class); }
        }
        long next;
        try (var update = connection.prepareStatement("UPDATE dalmaru.run_party_control SET roster_revision=roster_revision+1, leader_character_id=COALESCE(?::uuid, leader_character_id) WHERE run_id=? RETURNING roster_revision")) {
            update.setObject(1, characterId.equals(leader) ? successor : null);
            update.setObject(2, runId);
            try (var row = update.executeQuery()) {
                row.next();
                next = row.getLong(1);
            }
        }
        if (successor == null) {
            try (var update = connection.prepareStatement("UPDATE dalmaru.run_instances SET state='FAILED', finished_at=CURRENT_TIMESTAMP WHERE run_id=? AND state IN ('ACTIVE','PAUSED')")) {
                update.setObject(1, runId);
                update.executeUpdate();
            }
        }
        return new MemberExit(ExitStatus.REMOVED, runId, characterId, next, checkpoint);
    }
}
