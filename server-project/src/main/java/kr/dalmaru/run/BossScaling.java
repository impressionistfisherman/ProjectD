package kr.dalmaru.run;

import kr.dalmaru.content.BeaconMechanics;
import kr.dalmaru.content.ContentCatalog;

/** Boss numbers by starting party size and difficulty (P4-S04). Each factor is applied exactly once. */
public final class BossScaling {
    private BossScaling() {}

    /** Training x1.38 comes from {@link RunEncounterRules#bossHealth}; the party factor is the J02 health scale. */
    public static double health(ContentCatalog content, RunDifficulty difficulty, double base, int startingPartySize) {
        return RunEncounterRules.bossHealth(difficulty, base) * BeaconTowerMobs.healthScale(content, startingPartySize);
    }

    /** Training x1.15 times the J02 damage scale. Mobs keep their own path and are not scaled here. */
    public static double damage(ContentCatalog content, RunDifficulty difficulty, int base, int startingPartySize) {
        return base * (difficulty == RunDifficulty.TRAINING ? 1.15 : 1.0) * BeaconTowerMobs.damageScale(content, startingPartySize);
    }

    public static double maximumPosture(BeaconMechanics.Boss rules, boolean finalBoss, RunDifficulty difficulty, int startingPartySize) {
        double base = finalBoss ? rules.beaconPosture() : rules.gatekeeperPosture();
        return base * rules.postureScales().get(BeaconTowerMobs.tier(startingPartySize))
                * (difficulty == RunDifficulty.TRAINING ? rules.trainingPostureScale() : 1.0);
    }
}
