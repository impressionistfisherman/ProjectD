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

    /** Boss bar only. Player {@link #maximumHealth} still rejects above 1024. RPG health stays authoritative; the engine bar is at most 1024 and damage is scaled by the same ratio. */
    public static float cappedEngineMaximum(double rpgMaximum) {
        if (!Double.isFinite(rpgMaximum) || rpgMaximum <= 0) throw new IllegalArgumentException("Invalid RPG amount.");
        return (float) Math.min(rpgMaximum / 20.0, 1024.0);
    }

    public static float scaleEngineDamage(float engineDamage, double rpgMaximum) {
        if (!Float.isFinite(engineDamage) || engineDamage < 0) throw new IllegalArgumentException("Invalid engine damage.");
        double uncapped = rpgMaximum / 20.0;
        if (uncapped <= 1024.0) return engineDamage;
        return (float) (engineDamage * (cappedEngineMaximum(rpgMaximum) / uncapped));
    }

    public static double hudRpg(double rpgMaximum, float engineHealth, float engineMaximum) {
        if (!Double.isFinite(rpgMaximum) || rpgMaximum < 0 || !Float.isFinite(engineHealth) || engineHealth < 0 || !Float.isFinite(engineMaximum) || engineMaximum < 0) throw new IllegalArgumentException("Invalid health ratio.");
        if (engineMaximum == 0) return 0;
        return rpgMaximum * (engineHealth / engineMaximum);
    }
}
