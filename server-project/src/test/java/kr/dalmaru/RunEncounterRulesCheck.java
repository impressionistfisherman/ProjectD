package kr.dalmaru;

import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunEncounterRules;

public final class RunEncounterRulesCheck {
    public static void main(String[] args) {
        if (RunEncounterRules.requiredBrazierInteractions(1) != 1 || RunEncounterRules.requiredBrazierInteractions(2) != 1 || RunEncounterRules.requiredBrazierInteractions(3) != 2 || RunEncounterRules.requiredBrazierInteractions(4) != 2 || Math.abs(RunEncounterRules.bossHealth(RunDifficulty.TRAINING, 10800) - 14904) > .001) throw new IllegalStateException("Run encounter rules failed.");
        String[][] weeks={
            {"2026-10-07T14:59:59Z","2026-10-01"},
            {"2026-10-07T15:00:00Z","2026-10-01"},
            {"2026-10-07T20:59:59.999Z","2026-10-01"},
            {"2026-10-07T21:00:00Z","2026-10-08"},
            {"2026-10-07T21:00:00.001Z","2026-10-08"},
            {"2026-10-14T14:59:59Z","2026-10-08"},
            {"2026-10-14T20:59:59.999Z","2026-10-08"},
            {"2026-10-14T21:00:00Z","2026-10-15"},
            {"2025-12-31T20:59:59.999Z","2025-12-25"},
            {"2025-12-31T21:00:00Z","2026-01-01"}
        };
        for(String[] week:weeks)if(!RunEncounterRules.weekId(java.time.Instant.parse(week[0])).equals(java.time.LocalDate.parse(week[1])))throw new AssertionError("J15 KST weekly cutoff: "+week[0]);
        long now=1_000_000L;
        if(RunEncounterRules.lingering(0,now)||!RunEncounterRules.lingering(now,now)||!RunEncounterRules.lingering(now-9_999L,now)||RunEncounterRules.lingering(now-10_000L,now))throw new AssertionError("J16 linger window");
        if(!RunEncounterRules.countsAsSurvivor(true,false,0,now)||RunEncounterRules.countsAsSurvivor(true,true,0,now)||!RunEncounterRules.countsAsSurvivor(false,false,now,now)||RunEncounterRules.countsAsSurvivor(false,false,now-10_000L,now)||RunEncounterRules.countsAsSurvivor(false,true,now,now))throw new AssertionError("J16 survivor count");
        System.out.println("Run encounter rules and Thursday 06:00 KST boundary verified.");
    }
}
