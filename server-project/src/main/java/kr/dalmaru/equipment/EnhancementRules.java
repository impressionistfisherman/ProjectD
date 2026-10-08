package kr.dalmaru.equipment;

public final class EnhancementRules {
	private EnhancementRules() { }
	public static int chance(int enhancement, int failures) {
		if (enhancement < 0 || enhancement >= 15 || failures < 0) throw new IllegalArgumentException("Invalid enhancement state.");
		int[] base = {100, 95, 90, 85, 80, 70, 60, 50, 40, 30, 25, 20, 15, 10, 5};
		return Math.min(100, base[enhancement] + failures * 5);
	}
}
