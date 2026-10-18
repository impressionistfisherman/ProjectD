package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.run.*;

public final class BossPostureCheck {
    public static void main(String[] args) throws Exception {
        var content = ContentCatalog.current();
        var rules = content.mechanics().boss();
        check(rules.gatekeeperPosture() == 650 && rules.beaconPosture() == 900 && rules.wallPostureDamage() == 225
                && rules.collapseTicks() == 80 && rules.collapsedDamageScale() == 1.3 && rules.immunityTicks() == 300, "Frozen boss posture content");
        check(rules.risingStrikePosture(1) == 18 && rules.risingStrikePosture(5) == 30
                && rules.risingStrikePosture(3) > 18 && rules.risingStrikePosture(3) < 30, "Rising strike posture damage 18..30");

        var posture = new BossPosture(650, rules);
        posture.tick(0);
        check(posture.state() == RunMechanicAccess.Posture.NORMAL && posture.posture() == 650 && posture.damageTakenScale() == 1, "Initial state");
        check(!posture.damage(0) && !posture.damage(-5) && !posture.damage(Double.NaN) && !posture.damage(Double.POSITIVE_INFINITY) && posture.posture() == 650, "Invalid amounts ignored");
        check(!posture.damage(649) && posture.posture() == 1, "Partial damage");
        check(posture.damage(1) && posture.state() == RunMechanicAccess.Posture.COLLAPSED && posture.damageTakenScale() == 1.3
                && posture.posture() == 0 && posture.remainingTicks() == 80, "Collapse start");
        check(!posture.damage(1000) && !posture.wall(1, 225) && posture.state() == RunMechanicAccess.Posture.COLLAPSED && posture.remainingTicks() == 80, "Simultaneous/extra hit must not restart collapse");
        for (int tick = 1; tick <= 79; tick++) {
            posture.tick(tick);
            posture.tick(tick); // same tick twice must age once
            check(posture.state() == RunMechanicAccess.Posture.COLLAPSED && posture.damageTakenScale() == 1.3, "Collapse ended early at " + tick);
        }
        check(posture.remainingTicks() == 1, "79 boundary");
        posture.tick(80);
        check(posture.state() == RunMechanicAccess.Posture.IMMUNE && posture.posture() == 650 && posture.damageTakenScale() == 1 && posture.remainingTicks() == 300, "Collapse end restores full posture and starts immunity");
        check(!posture.damage(10000) && !posture.wall(2, 10000) && posture.posture() == 650 && posture.state() == RunMechanicAccess.Posture.IMMUNE, "Immunity must ignore and not accumulate");
        for (int tick = 81; tick <= 379; tick++) {
            posture.tick(tick);
            check(posture.state() == RunMechanicAccess.Posture.IMMUNE, "Immunity ended early at " + tick);
        }
        check(posture.remainingTicks() == 1 && posture.posture() == 650, "379 boundary");
        posture.tick(380);
        check(posture.state() == RunMechanicAccess.Posture.NORMAL && posture.posture() == 650 && posture.remainingTicks() == 0, "Immunity end");
        check(!posture.damage(649) && posture.damage(1), "Collapse works again after immunity with full gauge");

        var wall = new BossPosture(650, rules);
        check(!wall.wall(1, 225) && wall.posture() == 425, "First wall impact");
        check(!wall.wall(1, 225) && wall.posture() == 425, "Same charge must hit once");
        check(!wall.wall(0, 225) && !wall.wall(-1, 225) && wall.posture() == 425, "Invalid charge id");
        check(!wall.wall(2, 225) && wall.posture() == 200 && wall.wall(3, 225) && wall.state() == RunMechanicAccess.Posture.COLLAPSED, "Next charges accumulate to collapse");
        var consumed = new BossPosture(650, rules);
        consumed.damage(650);
        check(!consumed.wall(7, 225), "Collapsed boss ignores wall");
        for (int tick = 1; tick <= 80; tick++) consumed.tick(tick);
        for (int tick = 81; tick <= 380; tick++) consumed.tick(tick);
        check(consumed.state() == RunMechanicAccess.Posture.NORMAL && !consumed.wall(7, 225) && consumed.posture() == 650, "A charge consumed while collapsed cannot hit later");

        var first = new BossPosture(650, rules);
        var second = new BossPosture(900, rules);
        first.damage(650);
        check(first.state() == RunMechanicAccess.Posture.COLLAPSED && second.state() == RunMechanicAccess.Posture.NORMAL && second.posture() == 900 && second.damageTakenScale() == 1, "Per-boss isolation");
        boolean rejected = false;
        try { new BossPosture(0, rules); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Zero maximum accepted");

        var charge = new BossCharge();
        check(!charge.active() && charge.tick(true) == 0, "Idle charge reports nothing");
        long id = charge.start();
        check(id == 1 && charge.active() && charge.tick(true) == 0 && charge.tick(true) == 0, "Impact before real travel must be ignored");
        check(charge.tick(true) == id && !charge.active() && charge.tick(true) == 0, "First wall impact once");
        long open = charge.start();
        for (int tick = 1; tick <= BossCharge.MAXIMUM_TICKS; tick++) check(charge.tick(false) == 0, "No collision no impact");
        check(open == 2 && !charge.active() && charge.tick(true) == 0, "Open-field charge ends without impact");
        long cancelled = charge.start();
        charge.cancel();
        check(cancelled == 3 && !charge.active() && charge.tick(true) == 0, "Cancelled charge");

        var runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        var combat = Files.readString(Path.of("src/main/java/kr/dalmaru/combat/CombatRuntime.java"));
        check(runtime.contains("charge.tick(boss.horizontalCollision)") && runtime.contains("posture.wall(wall,"), "Wall impact must come from the boss body collision");
        check(runtime.contains("public boolean bossSkillHit(") && runtime.contains("public double bossDamageTaken(") && runtime.contains("postureHits.put(attacker,tickCount)"), "Boss hooks missing");
        check(runtime.contains("bossViews(e)") && !runtime.contains("neglectActive?e.neglect.remaining():0,List.of())"), "Snapshot must expose bosses");
        check(combat.contains("bossDamageTaken.applyAsDouble(target.getUUID())") && combat.contains("!basic&&damage>0&&mechanics!=null&&risingStrikeCooldowns.getOrDefault(player.getUUID(),0L)-skill(player.getUUID(),\"skill.warrior.rising_strike\").cooldownTicks()==tick")
                && combat.contains("risingStrikePosture(rank("), "Posture hit must be tied to an actual rising strike damage");
        check(combat.contains("risingStrikeCooldowns.put(playerId, tick + definition.cooldownTicks());"), "Cast tick must derive from the cooldown written by the cast");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
