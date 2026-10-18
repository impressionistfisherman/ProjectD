package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import kr.dalmaru.run.BeaconTowerMobs;
import kr.dalmaru.run.RunEncounterRules;

/**
 * P4-S10 zone progression contract: 24 fixed placements and two bosses in a fixed order, no second source of
 * monsters (no endless respawn), doors that open only at their step, and a party size that is fixed at entry so that
 * leaving never weakens the encounter. The runtime itself needs a live world, so the order is pinned at the source.
 */
public final class P4RunProgressionCheck {
    private P4RunProgressionCheck() { }

    public static void main(String[] args) throws Exception {
        // Content: 5 + 5 + 9 + 5 placements, six kinds, one commander in the last group only.
        int[] counts = {5, 5, 9, 5};
        int total = 0;
        for (int group = 0; group < 4; group++) {
            check(BeaconTowerMobs.group(group).size() == counts[group], "Group " + group + " size changed");
            total += BeaconTowerMobs.group(group).size();
        }
        check(total == 24, "Placements are not 24");
        for (int group = 0; group < 3; group++) check(!BeaconTowerMobs.group(group).contains(BeaconTowerMobs.Kind.COMMANDER), "Commander outside the last group");
        check(BeaconTowerMobs.group(3).stream().filter(kind -> kind == BeaconTowerMobs.Kind.COMMANDER).count() == 1, "Last group lacks exactly one commander");
        try { BeaconTowerMobs.group(4); throw new AssertionError("A fifth group exists"); } catch (IllegalArgumentException expected) { }
        for (int size = 1; size <= 4; size++) check(RunEncounterRules.requiredBrazierInteractions(size) == (size >= 3 ? 2 : 1), "Brazier requirement changed for " + size);
        for (int bad : new int[] {0, 5}) {
            try { RunEncounterRules.requiredBrazierInteractions(bad); throw new AssertionError("Invalid party size accepted"); } catch (IllegalArgumentException expected) { }
        }

        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));

        // Groups spawn from exactly four places, each with its own group number.
        for (int group = 0; group < 4; group++) check(count(runtime, "spawnGroup(encounter," + group + ")") == 1, "Group " + group + " does not spawn from exactly one place");
        check(count(runtime, "spawnGroup(") == 5, "A fifth group spawn path exists (definition plus four steps expected)");
        check(count(runtime, "spawnMob(encounter") == 3, "Monsters spawn outside the group steps and the single commander summon");
        check(count(runtime, "encounter.summoned=true;spawnMob(encounter,BeaconTowerMobs.Kind.SOLDIER,5);spawnMob(encounter,BeaconTowerMobs.Kind.SOLDIER,6)") == 1
                && runtime.contains("kind==BeaconTowerMobs.Kind.COMMANDER&&!encounter.summoned&&"), "Commander reinforcements can repeat");

        // Stage order GATEKEEPER -> BRAZIER -> APPROACH -> BEACON -> COMPLETED, each reached from one place only.
        check(Pattern.compile("\\.stage\\s*=(?!=)").matcher(runtime).results().count() == 4, "A stage change appears outside the four steps");
        for (String step : new String[] {"encounter.stage = Stage.BRAZIER", "encounter.stage=Stage.APPROACH", "encounter.stage = Stage.BEACON", "encounter.stage = Stage.COMPLETED"})
            check(count(runtime, step) == 1, "Stage step is missing or repeated: " + step);
        check(runtime.contains("private enum Stage { GATEKEEPER, BRAZIER, APPROACH, BEACON, COMPLETED }") && runtime.contains("private Stage stage = Stage.GATEKEEPER"), "Stage order or start changed");
        String killed = method(runtime, "public UUID bossKilled(");
        check(killed.contains("!bossId.equals(encounter.bossId)") && killed.indexOf("encounter.stage == Stage.GATEKEEPER") < killed.indexOf("encounter.stage == Stage.BEACON"), "Boss kill is not matched to the live boss and stage");
        check(killed.contains("encounter.bossId = null;") && killed.indexOf("encounter.stage = Stage.BRAZIER") < killed.indexOf("spawnGroup(encounter,2)"), "A repeated gatekeeper kill could advance twice");
        check(killed.contains("BeaconTowerMap.open(encounter.level, encounter.floor, 27)") && killed.contains("BeaconTowerMap.open(encounter.level, encounter.floor, 64)") && killed.indexOf("Stage.COMPLETED") > killed.indexOf("(encounter.stage == Stage.BEACON)"), "Gates do not open at their kill");
        String mobs = method(runtime, "private void tickMobs(");
        int gate = mobs.indexOf("if(!encounter.mobs.isEmpty())return;");
        check(gate > 0 && gate < mobs.indexOf("spawnGroup(encounter,1)") && gate < mobs.indexOf("spawnBoss(") && gate < mobs.indexOf("spawnFinalBoss(encounter)"), "A boss or group can appear before the required kills");
        check(mobs.contains("if(encounter.group==0){") && mobs.contains("encounter.stage==Stage.GATEKEEPER&&encounter.bossId==null") && mobs.contains("encounter.stage==Stage.APPROACH"), "Group or boss step is not guarded by its stage");
        String finalGate = method(runtime, "private void activateFinalBoss(");
        check(finalGate.contains("encounter.mobs.isEmpty()") && count(finalGate, "encounter.mobs.isEmpty()") == 2 && finalGate.indexOf("Stage.APPROACH") > finalGate.indexOf("return;"), "Purification can be bypassed with monsters alive");
        check(finalGate.contains("BeaconTowerMap.open(encounter.level,encounter.floor,45)") && finalGate.indexOf("BeaconTowerMap.open") < finalGate.indexOf("spawnGroup(encounter,3)"), "Final gate does not open before the last group");
        check(count(runtime, "BeaconTowerMap.open(") == 4, "A gate opens from an unexpected place");

        // Players stay in the arena; strangers cannot enter it.
        check(runtime.contains("if(!inside(player,encounter))player.teleportTo(encounter.level,entry.getValue().lastSafe"), "Participants can leave the arena");
        check(runtime.contains("if(runId(player)==null&&region(player)!=null)player.connection.disconnect("), "Strangers can enter an arena");

        // Party size is fixed at entry: nothing but the constructor assigns it and leaving never touches it.
        check(Pattern.compile("\\.partySize\\s*=(?!=)").matcher(runtime).results().count() == 1 && runtime.contains("this.partySize=partySize"), "Party size can change after entry");
        check(!method(runtime, "private void publishExit(").contains("partySize") && !method(runtime, "private void completeExit(").contains("partySize") && !method(runtime, "public void disconnect(").contains("partySize"), "Leaving or disconnecting touches the fixed party size");
        System.out.println("Progression 24 placements/5-5-9-5/one commander, single spawn sources, stage and gate order, no repeat advance, arena bounds, fixed party size PASS (source contract; in-world run not performed)");
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Missing " + signature);
        int open = source.indexOf('{', start), depth = 1, end = open + 1;
        for (; depth > 0; end++) { char value = source.charAt(end); if (value == '{') depth++; else if (value == '}') depth--; }
        return source.substring(start, end);
    }

    private static int count(String text, String needle) {
        int total = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) total++;
        return total;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
