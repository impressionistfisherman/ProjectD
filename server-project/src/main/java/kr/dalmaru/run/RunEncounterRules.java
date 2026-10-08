package kr.dalmaru.run;

public final class RunEncounterRules {
    private RunEncounterRules() { }
    public static int requiredBrazierInteractions(int partySize) { if (partySize < 1 || partySize > 4) throw new IllegalArgumentException("Invalid party size."); return partySize >= 3 ? 2 : 1; }
    public static double bossHealth(RunDifficulty difficulty, double firstStepHealth) { return difficulty == RunDifficulty.TRAINING ? firstStepHealth * 1.38 : firstStepHealth; }
}
