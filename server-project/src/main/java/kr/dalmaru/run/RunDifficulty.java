package kr.dalmaru.run;

public enum RunDifficulty {
    FIRST_STEP(15, 5), TRAINING(20, 3), TRIAL(30, 2), EXTREME(60, 1);
    public static final long DUNGEON_DURATION_MILLIS = 45L * 60L * 1000L;
    private final int requiredLevel;
    private final int revives;
    RunDifficulty(int requiredLevel, int revives) { this.requiredLevel = requiredLevel; this.revives = revives; }
    public int requiredLevel() { return requiredLevel; }
    public int revives() { return revives; }
    public int weeklyLimit() { return 3; }
    public long durationMillis() { return DUNGEON_DURATION_MILLIS; }
}
