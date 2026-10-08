package kr.dalmaru.run;

import java.util.UUID;

public record RunRecord(UUID runId, RunDifficulty difficulty, int partySize, int scalingTier, String state, boolean replayed) { }
