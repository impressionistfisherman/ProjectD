package kr.dalmaru.equipment;

public final class EnhancementRules {
	private EnhancementRules() { }
	public static int chance(int enhancement, int failures) {
		if (enhancement < 0 || enhancement >= 15 || failures < 0) throw new IllegalArgumentException("Invalid enhancement state.");
		int[] base = {100, 95, 90, 85, 80, 70, 60, 50, 40, 30, 25, 20, 15, 10, 5};
		return Math.min(100, base[enhancement] + failures * 5);
	}
    public record Cost(int copper,int ironstone) { }
    public static Cost cost(int target) {
        // shortcut: prices beyond +5 are undefined in the pilot, enable only when versioned content supplies them.
        if(target<1||target>5)throw new IllegalArgumentException("Enhancement price is not defined for this tier.");
        return new Cost(new int[]{8,12,18,26,36}[target-1],new int[]{0,1,1,2,2}[target-1]);
    }
}
