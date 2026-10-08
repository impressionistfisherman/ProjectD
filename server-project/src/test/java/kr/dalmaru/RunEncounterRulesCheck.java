package kr.dalmaru;

import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunEncounterRules;

public final class RunEncounterRulesCheck { public static void main(String[] args) { if (RunEncounterRules.requiredBrazierInteractions(1) != 1 || RunEncounterRules.requiredBrazierInteractions(2) != 1 || RunEncounterRules.requiredBrazierInteractions(3) != 2 || RunEncounterRules.requiredBrazierInteractions(4) != 2 || Math.abs(RunEncounterRules.bossHealth(RunDifficulty.TRAINING, 10800) - 14904) > .001) throw new IllegalStateException("Run encounter rules failed."); System.out.println("Run encounter rules verified."); } }
