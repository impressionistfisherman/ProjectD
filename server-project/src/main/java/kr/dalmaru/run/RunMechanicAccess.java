package kr.dalmaru.run;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread contract. S02/S04/S07 supply implementations; absence is unavailable, never success. */
public interface RunMechanicAccess {
    enum Phase { GATEKEEPER, BRAZIER, APPROACH, BEACON, COMPLETED }
    enum Result { STARTED, CANCELLED, ALREADY_CHANNELING, NOT_CHANNELING, UNAVAILABLE,
        STALE_REVISION, NOT_PARTICIPANT, WRONG_PHASE, DEAD, OUT_OF_RANGE, NO_LINE_OF_SIGHT, BUSY, ALREADY_LIT, MOBS_REMAINING }
    enum CancelReason { REQUEST, MOVED, HIT, SKILL, DEATH, DISCONNECT, DIMENSION, PHASE }
    enum Neglect { IDLE, WARNING, DAMAGING }
    enum Posture { NORMAL, COLLAPSED, IMMUNE }
    record BrazierView(BlockPos position, boolean lit, UUID channelPlayerId, int remainingTicks) {
        public BrazierView { position = position.immutable(); }
    }
    record BossView(UUID entityId, double health, double maximumHealth, double posture,
                    double maximumPosture, Posture state, int remainingTicks) {}
    record Snapshot(UUID runId, long revision, Phase phase, int startingPartySize,
                    List<BrazierView> braziers, Neglect neglect, int neglectRemainingTicks, List<BossView> bosses) {
        public Snapshot { braziers = List.copyOf(braziers); bosses = List.copyOf(bosses); }
    }
    Result startBrazier(ServerPlayer player, UUID runId, long revision, BlockPos position);
    Result cancelBrazier(ServerPlayer player, UUID runId, long revision);
    void skillUsed(ServerPlayer player);
    /** Called only after actual health/shield damage; dodged/immune hits are excluded. */
    void receivedHit(ServerPlayer player);
    Snapshot mechanicSnapshot(ServerPlayer player);

    enum ExitReason { VOLUNTARY, KICKED, RECONNECT_EXPIRED }
    enum ExitStatus { REMOVED, ALREADY_REMOVED, STALE_REVISION, NOT_PARTICIPANT, WRONG_PHASE }
    record MemberExit(ExitStatus status, UUID runId, UUID characterId, long revision, boolean returnPending) {}
    /** Caller owns account/request/run locks and transaction. Publish runtime removal only after commit.
     * Must cancel S12 eligibility and mark return pending atomically; retain existing return ACK.
     */
    @FunctionalInterface interface MemberExitAdapter {
        MemberExit removeMember(Connection connection, UUID runId, UUID characterId,
                                long expectedRevision, ExitReason reason) throws SQLException;
    }
}
