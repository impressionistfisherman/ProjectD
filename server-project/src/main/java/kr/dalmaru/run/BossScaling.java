package kr.dalmaru.run;

import kr.dalmaru.content.BeaconMechanics;
import kr.dalmaru.content.ContentCatalog;

/** Boss numbers by starting party size and difficulty (P4-S04). Each factor is applied exactly once. */
public final class BossScaling {
    private BossScaling() {}

    /** Training x1.38 stays fixed. Trial and extreme factors come from the content boss block. The party factor is the J02 health scale. */
    public static double health(ContentCatalog content, RunDifficulty difficulty, double base, int startingPartySize) {
        var rules = content.mechanics() == null ? null : content.mechanics().boss();
        return base * RunEncounterRules.healthFactor(difficulty, rules) * BeaconTowerMobs.healthScale(content, startingPartySize);
    }

    /** Training x1.15 stays fixed. Trial and extreme damage factors come from content. Mobs keep their own path and are not scaled here. */
    public static double damage(ContentCatalog content, RunDifficulty difficulty, int base, int startingPartySize) {
        var rules = content.mechanics() == null ? null : content.mechanics().boss();
        return base * RunEncounterRules.damageFactor(difficulty, rules) * BeaconTowerMobs.damageScale(content, startingPartySize);
    }

    public static double maximumPosture(BeaconMechanics.Boss rules, boolean finalBoss, RunDifficulty difficulty, int startingPartySize) {
        double base = finalBoss ? rules.beaconPosture() : rules.gatekeeperPosture();
        return base * rules.postureScales().get(BeaconTowerMobs.tier(startingPartySize))
                * RunEncounterRules.postureFactor(difficulty, rules);
    }
}
