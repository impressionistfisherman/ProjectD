package kr.dalmaru.run;

/**
 * Tracks one boss charge at a time. A wall impact is a real block collision after the boss has actually travelled
 * for a few ticks; each charge reports at most one impact and ends on it.
 */
public final class BossCharge {
    public static final int MAXIMUM_TICKS = 16, MINIMUM_TICKS = 3;
    private long id, nextId = 1;
    private int elapsed;
    private boolean active;

    /** Starts a new charge and returns its id. A running charge is replaced by the new one. */
    public long start() { id = nextId++; elapsed = 0; active = true; return id; }
    public boolean active() { return active; }
    public long id() { return id; }

    /** Advances one live tick; returns the charge id when this tick is the first wall impact, otherwise 0. */
    public long tick(boolean horizontalCollision) {
        if (!active) return 0;
        elapsed++;
        if (horizontalCollision && elapsed >= MINIMUM_TICKS) { active = false; return id; }
        if (elapsed >= MAXIMUM_TICKS) active = false;
        return 0;
    }
    public void cancel() { active = false; }
}
