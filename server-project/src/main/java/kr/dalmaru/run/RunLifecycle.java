package kr.dalmaru.run;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class RunLifecycle {
    public enum Phase { PREPARING, ACTIVE, CLOSING, CLOSED }
    public record Owner(UUID accountId, long generation, long requestId) {
        public Owner {
            if (accountId == null || generation < 1 || requestId < 1) throw new IllegalArgumentException("Invalid run owner.");
        }
    }

    private final Owner owner;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.PREPARING);

    public RunLifecycle(Owner owner) { this.owner = java.util.Objects.requireNonNull(owner); }
    public boolean owns(Owner candidate) { return owner.equals(candidate); }
    public Phase phase() { return phase.get(); }
    public boolean preparing(Owner candidate) { return owns(candidate) && phase.get() == Phase.PREPARING; }
    public boolean joinable() { return phase.get() == Phase.ACTIVE; }
    public boolean activate(Owner candidate) {
        if (!owns(candidate)) return false;
        return phase.compareAndSet(Phase.PREPARING, Phase.ACTIVE) || phase.get() == Phase.ACTIVE;
    }
    public boolean beginClose() {
        while (true) {
            Phase current = phase.get();
            if (current == Phase.CLOSING || current == Phase.CLOSED) return false;
            if (phase.compareAndSet(current, Phase.CLOSING)) return true;
        }
    }
    public void closed() {
        if (!phase.compareAndSet(Phase.CLOSING, Phase.CLOSED) && phase.get() != Phase.CLOSED) throw new IllegalStateException("Run close was not owned.");
    }
}
