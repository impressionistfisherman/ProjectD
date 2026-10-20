package kr.dalmaru.run;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

public final class RunEncounterRules {
    public static final long DISCONNECT_LINGER_MS=10_000L;
    public static boolean clickRevive(long elapsedTicks){return elapsedTicks>=100;}
    public static boolean autoRevive(long elapsedTicks){return elapsedTicks>=300;}
    public static final long RECONNECT_WINDOW_MS=180_000L;
    /** A disconnected member may come back while now <= disconnectAt+180s; zero means connected. */
    public static boolean reconnectAllowed(long disconnectedAt,long now){return disconnectedAt==0||now-disconnectedAt<=RECONNECT_WINDOW_MS;}
    /** Whole-run pause is capped at the same 180 seconds; the individual window and run limits still take precedence. */
    public static boolean pauseExpired(long pausedAt,long now){return pausedAt!=0&&now-pausedAt>RECONNECT_WINDOW_MS;}
    /** Whole-run pause start after a presence check: a living member lifts it (revive/reconnect); nobody keeps or starts it. */
    public static long pausedAfterCheck(long pausedAt,boolean survivorPresent,long now){return survivorPresent?0:(pausedAt!=0?pausedAt:now);}
    /** Final-failure chat line: "revives used up" only when none are left; a party wipe with revives left says so. */
    public static String finalDeathMessage(int revivesRemaining){return revivesRemaining>0?"전원이 쓰러져 폐봉수대 공략에 실패했습니다.":"부활 횟수를 모두 사용해 폐봉수대 공략에 실패했습니다.";}
    public static boolean lingering(long disconnectedAt,long now){return disconnectedAt!=0&&now-disconnectedAt<DISCONNECT_LINGER_MS;}
    public static boolean countsAsSurvivor(boolean connected,boolean dead,long disconnectedAt,long now){return !dead&&(connected||lingering(disconnectedAt,now));}

    private RunEncounterRules() { }
    public static LocalDate weekId(Instant instant) {
        return instant.atZone(ZoneId.of("Asia/Seoul")).minusHours(6).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY));
    }
    public static int requiredBrazierInteractions(int partySize) { if (partySize < 1 || partySize > 4) throw new IllegalArgumentException("Invalid party size."); return partySize >= 3 ? 2 : 1; }
    public static double healthFactor(RunDifficulty difficulty, kr.dalmaru.content.BeaconMechanics.Boss rules) {
        return switch (difficulty) {
            case TRAINING -> 1.38;
            case TRIAL -> rules == null ? 1 : rules.trialHealthScale();
            case EXTREME -> rules == null ? 1 : rules.extremeHealthScale();
            case FIRST_STEP -> 1;
        };
    }
    public static double damageFactor(RunDifficulty difficulty, kr.dalmaru.content.BeaconMechanics.Boss rules) {
        return switch (difficulty) {
            case TRAINING -> 1.15;
            case TRIAL -> rules == null ? 1 : rules.trialDamageScale();
            case EXTREME -> rules == null ? 1 : rules.extremeDamageScale();
            case FIRST_STEP -> 1;
        };
    }
    public static double postureFactor(RunDifficulty difficulty, kr.dalmaru.content.BeaconMechanics.Boss rules) {
        return switch (difficulty) {
            case TRAINING -> rules.trainingPostureScale();
            case TRIAL -> rules.trialPostureScale();
            case EXTREME -> rules.extremePostureScale();
            case FIRST_STEP -> 1;
        };
    }
    public static double bossHealth(RunDifficulty difficulty, double firstStepHealth) {
        var mechanics = kr.dalmaru.content.ContentCatalog.current().mechanics();
        return firstStepHealth * healthFactor(difficulty, mechanics == null ? null : mechanics.boss());
    }
}
