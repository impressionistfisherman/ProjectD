package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.run.*;

public final class BossPartyScalingCheck {
    public static void main(String[] args) throws Exception {
        var content = ContentCatalog.current();
        var rules = content.mechanics().boss();
        double[] health = {1, 1.8, 3.25, 3.25}, damage = {1, 1.12, 1.25, 1.25}, posture = {1, 1.7, 2.8, 2.8};
        for (int size = 1; size <= 4; size++) {
            for (var difficulty : RunDifficulty.values()) {
                boolean training = difficulty == RunDifficulty.TRAINING;
                for (int base : new int[]{content.encounter().gatekeeperHealth(), content.encounter().finalBossHealth()})
                    same(BossScaling.health(content, difficulty, base, size), base * health[size - 1] * (training ? 1.38 : 1), "Boss health " + size + difficulty);
                for (int base : new int[]{110, 120, 130, 150, 180})
                    same(BossScaling.damage(content, difficulty, base, size), base * damage[size - 1] * (training ? 1.15 : 1), "Boss damage " + size + difficulty);
                same(BossScaling.maximumPosture(rules, false, difficulty, size), 650 * posture[size - 1] * (training ? 1.2 : 1), "Gatekeeper posture " + size + difficulty);
                same(BossScaling.maximumPosture(rules, true, difficulty, size), 900 * posture[size - 1] * (training ? 1.2 : 1), "Beacon posture " + size + difficulty);
            }
        }
        for (int size : new int[]{0, 5, -1}) {
            boolean rejected = false;
            try { BossScaling.health(content, RunDifficulty.FIRST_STEP, 100, size); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid party size accepted: " + size);
        }
        // Training factors are applied once: the ratio to the normal value is exactly the training factor.
        for (int size = 1; size <= 4; size++) {
            same(BossScaling.health(content, RunDifficulty.TRAINING, 1000, size) / BossScaling.health(content, RunDifficulty.FIRST_STEP, 1000, size), 1.38, "Training health doubled");
            same(BossScaling.damage(content, RunDifficulty.TRAINING, 100, size) / BossScaling.damage(content, RunDifficulty.FIRST_STEP, 100, size), 1.15, "Training damage doubled");
        }

        var runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        int boss = runtime.indexOf("    private void tickBoss("), after = runtime.indexOf("    private List<BossView> bossViews(");
        String bossCode = runtime.substring(boss, after);
        check(!bossCode.contains(" attack(encounter") && !bossCode.contains("(attack(encounter"), "Boss patterns must use the scaled boss attack");
        int mobs = runtime.indexOf("    private void tickMobs("), mobsEnd = runtime.indexOf("    private void tickBoss(");
        String mobCode = runtime.substring(mobs, mobsEnd);
        check(!mobCode.contains("bossAttack(") && mobCode.contains("attack(encounter,definition.damage())*BeaconTowerMobs.mobDamageScale(encounter.content,encounter.partySize)"), "Mobs must keep their single scale");
        // P4 balance: mob attack 0.11x (all groups, party scale kept), gatekeeper 9,300 HP with 1.8/3.25 party HP scale, untelegraphed gatekeeper attacks scaled once.
        for (int size = 1; size <= 3; size++) same(BeaconTowerMobs.mobDamageScale(content, size), damage[size - 1] * 0.11, "Mob damage scale " + size);
        same(BossScaling.health(content, RunDifficulty.FIRST_STEP, content.encounter().gatekeeperHealth(), 1), 9300, "Gatekeeper solo HP");
        same(BossScaling.health(content, RunDifficulty.FIRST_STEP, content.encounter().gatekeeperHealth(), 2), 16740, "Gatekeeper duo HP");
        same(BossScaling.health(content, RunDifficulty.FIRST_STEP, content.encounter().gatekeeperHealth(), 3), 30225, "Gatekeeper trio HP");
        check(bossCode.contains("gatekeeperAttack(encounter, 150)") && bossCode.contains("gatekeeperAttack(encounter, 130)") && bossCode.contains("gatekeeperAttack(encounter, 110)") && runtime.contains("bossAttack(encounter, base) * encounter.content.encounter().gatekeeperDamageScale()"), "Gatekeeper attacks must use the content scale once");
        check(bossCode.contains("startTelegraph(") && !runtime.substring(runtime.indexOf("private void startTelegraph("), runtime.indexOf("private void tickTelegraphs(")).contains("gatekeeperAttack("), "Telegraphed patterns must not be scaled again");
        check(runtime.contains("bossHealth(encounter, encounter.content.encounter().finalBossHealth())") && runtime.contains("bossHealth(encounter,encounter.content.encounter().gatekeeperHealth())"), "Boss spawn must use scaled health");
        check(runtime.contains("beginPosture(encounter,false)") && runtime.contains("beginPosture(encounter, true)"), "Each boss starts its own posture");
        check(runtime.split("partySize=partySize", -1).length == 2 && !runtime.contains("encounter.partySize=") && !runtime.contains("encounter.partySize--") && !runtime.contains("e.partySize="), "Starting party size must stay fixed");
    }

    private static void same(double actual, double expected, String message) { if (Math.abs(actual - expected) > 1e-9) throw new IllegalStateException(message + " expected " + expected + " got " + actual); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
