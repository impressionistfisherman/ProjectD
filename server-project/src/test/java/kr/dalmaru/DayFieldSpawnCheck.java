package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import kr.dalmaru.content.DayFieldRules;

public final class DayFieldSpawnCheck {
	public static void main(String[] args) throws Exception {
		check(!DayFieldRules.allows("minecraft:overworld", 0, 0), "Day spawn must stay out of the village center");
		check(!DayFieldRules.allows("minecraft:overworld", 18, 0), "Day spawn must stay out of the plaza plus margin");
		check(DayFieldRules.allows("minecraft:overworld", 19, 0), "Day spawn is allowed just outside the margin");
		check(!DayFieldRules.allows("minecraft:overworld", 400, 0), "Other villages keep the same exclusion");
		check(DayFieldRules.allows("minecraft:the_nether", 0, 0), "Overworld villages do not block another dimension");
		check(!DayFieldRules.allows(null, 19, 0), "A missing dimension is not a field spawn");
		String plazas = Files.readString(Path.of("src/main/java/kr/dalmaru/content/VillagePlazas.java"));
		check(plazas.contains("Math.min(10, town.radius())"), "Village plaza radius drifted from the day-spawn rule");
		String mixin = Files.readString(Path.of("src/main/java/kr/dalmaru/mixin/DayFieldSpawnMixin.java"));
		check(mixin.contains("isDarkEnoughToSpawn") && mixin.contains("isBrightOutside") && mixin.contains("getReturnValue") && mixin.contains("DayFieldRules.allows"), "Day spawn only opens the light check outside villages");
		check(mixin.contains("pos.getY() < surface - 1"), "Day spawn stays on the surface");
		check(Files.readString(Path.of("src/main/resources/dalmaru.mixins.json")).contains("\"DayFieldSpawnMixin\""), "Day field mixin is not registered");
		System.out.println("P6 day field spawn outside villages PASS");
	}

	private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
