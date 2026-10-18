package kr.dalmaru.run;

import kr.dalmaru.content.BeaconMechanics;
import kr.dalmaru.run.RunMechanicAccess.Posture;

/**
 * Posture gauge of one boss (P4-S04). Time advances only through {@link #tick(long)}, so an encounter that is
 * paused or failed does not age collapse/immunity. Per-boss state: a new boss gets a new instance.
 */
public final class BossPosture {
    private final double maximum;
    private final int collapseTicks, immunityTicks;
    private final double collapsedDamageScale;
    private double posture;
    private Posture state = Posture.NORMAL;
    private int remaining;
    private long lastTick = Long.MIN_VALUE, lastWallCharge;

    public BossPosture(double maximum, BeaconMechanics.Boss rules) {
        if (!(maximum >= 1) || Double.isInfinite(maximum)) throw new IllegalArgumentException("Invalid posture maximum.");
        this.maximum = maximum;
        this.posture = maximum;
        this.collapseTicks = rules.collapseTicks();
        this.immunityTicks = rules.immunityTicks();
        this.collapsedDamageScale = rules.collapsedDamageScale();
    }

    /** Advances at most once per server tick. A collapse ends with a full recovery followed by the immunity window. */
    public void tick(long serverTick) {
        if (serverTick == lastTick) return;
        lastTick = serverTick;
        if (state == Posture.NORMAL || --remaining > 0) return;
        if (state == Posture.COLLAPSED) {
            posture = maximum;
            state = Posture.IMMUNE;
            remaining = immunityTicks;
        } else {
            state = Posture.NORMAL;
            remaining = 0;
        }
    }

    /** Applies a posture hit. Returns true only for the single hit that starts a collapse. */
    public boolean damage(double amount) {
        if (state != Posture.NORMAL || !(amount > 0) || Double.isInfinite(amount)) return false;
        posture = Math.max(0, posture - amount);
        if (posture > 0) return false;
        state = Posture.COLLAPSED;
        remaining = collapseTicks;
        return true;
    }

    /** First real wall impact of a charge only. The charge is consumed even if the boss cannot be damaged right now. */
    public boolean wall(long chargeId, double amount) {
        if (chargeId <= 0 || chargeId == lastWallCharge) return false;
        lastWallCharge = chargeId;
        return damage(amount);
    }

    public double damageTakenScale() { return state == Posture.COLLAPSED ? collapsedDamageScale : 1; }
    public Posture state() { return state; }
    public double posture() { return state == Posture.COLLAPSED ? 0 : posture; }
    public double maximum() { return maximum; }
    public int remainingTicks() { return state == Posture.NORMAL ? 0 : remaining; }
}
