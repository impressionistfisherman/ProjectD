package kr.dalmaru.content;

/** Daytime field spawns stay outside the leveled village plus a margin. */
public final class DayFieldRules {
	public static final int PLAZA = 10;
	public static final int MARGIN = 8;

	private DayFieldRules() { }

	public static boolean allows(String dimension, int x, int z) {
		if (dimension == null || dimension.isBlank()) return false;
		for (var village : VillageCatalog.current()) {
			var town = village.zone();
			if (!dimension.equals(town.dimension())) continue;
			int radius = Math.min(PLAZA, town.radius()) + MARGIN;
			if (Math.abs((long) town.x() - x) <= radius && Math.abs((long) town.z() - z) <= radius) return false;
		}
		return true;
	}
}
