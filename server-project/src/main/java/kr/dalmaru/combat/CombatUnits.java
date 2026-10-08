package kr.dalmaru.combat;

/** One engine health unit represents 20 RPG units for the solo pilot. */
public final class CombatUnits {
    private CombatUnits() { }
    public static float toEngine(double rpg) {
        if (!Double.isFinite(rpg) || rpg < 0) throw new IllegalArgumentException("Invalid RPG amount.");
        return (float) (rpg / 20.0);
    }
    public static double toRpg(double engine) {
        if (!Double.isFinite(engine) || engine < 0) throw new IllegalArgumentException("Invalid engine amount.");
        return engine * 20.0;
    }
    public static float maximumHealth(double rpg) {
        float engine = toEngine(rpg);
        if (engine <= 0 || engine > 1024) throw new IllegalArgumentException("Health exceeds the solo pilot adapter range.");
        return engine;
    }
}
