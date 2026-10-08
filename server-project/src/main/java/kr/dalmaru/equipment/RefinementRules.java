package kr.dalmaru.equipment;

public final class RefinementRules {
    private RefinementRules() { }
    public static int chance(int grade, int failures) { if (grade < 0 || grade >= 5 || failures < 0) throw new IllegalArgumentException("Invalid refinement state."); return Math.min(100, new int[] { 90, 70, 45, 25, 10 }[grade] + failures * 10); }
}
