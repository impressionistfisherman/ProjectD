package kr.dalmaru.run;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

public final class RunEncounterRules {
    public static boolean clickRevive(long elapsedTicks){return elapsedTicks>=100;}
    public static boolean autoRevive(long elapsedTicks){return elapsedTicks>=300;}

    private RunEncounterRules() { }
    public static LocalDate weekId(Instant instant) {
        return instant.atZone(ZoneId.of("Asia/Seoul")).minusHours(6).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY));
    }
    public static int requiredBrazierInteractions(int partySize) { if (partySize < 1 || partySize > 4) throw new IllegalArgumentException("Invalid party size."); return partySize >= 3 ? 2 : 1; }
    public static double bossHealth(RunDifficulty difficulty, double firstStepHealth) { return difficulty == RunDifficulty.TRAINING ? firstStepHealth * 1.38 : firstStepHealth; }
}
