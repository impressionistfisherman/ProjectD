package kr.dalmaru;

import kr.dalmaru.combat.CombatUnits;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.run.BossScaling;
import kr.dalmaru.run.RunDifficulty;

/** Party bosses keep RPG health. The engine bar stops at 1024 and damage uses the same ratio. */
public final class BossEngineHealthCheck {
    public static void main(String[] args) {
        var content = ContentCatalog.current();
        int[] bases = {content.encounter().gatekeeperHealth(), content.encounter().finalBossHealth()};
        for (int size = 1; size <= 3; size++) for (int base : bases) {
            double rpg = BossScaling.health(content, RunDifficulty.FIRST_STEP, base, size);
            float engine = CombatUnits.cappedEngineMaximum(rpg);
            check(engine > 0 && engine <= 1024, "Spawn engine out of range " + size + " " + rpg);
            check(Math.abs(engine - Math.min(rpg / 20.0, 1024.0)) < 1e-3, "Engine cap " + rpg);
            float damage = CombatUnits.scaleEngineDamage(CombatUnits.toEngine(1000), rpg);
            float left = engine - damage;
            double hud = CombatUnits.hudRpg(rpg, left, engine);
            check(Math.abs(hud / rpg - left / engine) < 1e-4, "HUD ratio " + size + " " + base);
            check(Math.abs(hud - (rpg - 1000)) < 1.5, "HUD rpg after damage " + hud + " vs " + (rpg - 1000));
            float killing = CombatUnits.scaleEngineDamage(CombatUnits.toEngine(rpg), rpg);
            check(killing + 1e-2 >= engine, "Full RPG damage must empty the bar " + size + " " + base);
        }
        check(CombatUnits.scaleEngineDamage(CombatUnits.toEngine(100), 9300) == CombatUnits.toEngine(100), "Uncapped boss damage changed");
        check(CombatUnits.maximumHealth(9300) == 465 && CombatUnits.maximumHealth(14400) == 720, "Player adapter changed");
        boolean rejected = false;
        try { CombatUnits.maximumHealth(30225); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Player adapter must still reject a trio gatekeeper");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
