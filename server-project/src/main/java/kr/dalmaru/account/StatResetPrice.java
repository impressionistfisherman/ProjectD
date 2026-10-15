package kr.dalmaru.account;

public final class StatResetPrice {
	public static final int COPPER_PER_LEVEL = 10;

	private StatResetPrice() { }

	public static int copper(int level) { return Math.multiplyExact(level, COPPER_PER_LEVEL); }
}
