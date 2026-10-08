package kr.dalmaru.run;

public enum RunDifficulty {
    FIRST_STEP(15), TRAINING(20);
    private final int requiredLevel;
    RunDifficulty(int requiredLevel) { this.requiredLevel = requiredLevel; }
    public int requiredLevel() { return requiredLevel; }
}
