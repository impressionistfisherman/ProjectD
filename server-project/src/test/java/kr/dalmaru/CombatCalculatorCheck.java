package kr.dalmaru;

import java.util.UUID;
import kr.dalmaru.account.CharacterProfile;
import kr.dalmaru.combat.CombatCalculator;

public final class CombatCalculatorCheck {
	private CombatCalculatorCheck() { }

	public static void main(String[] args) {
		var profile = new CharacterProfile(UUID.fromString("00000000-0000-0000-0000-000000000008"), 10, 0, 0, 30, 0, 0, 10, 5, true);
		var stats = CombatCalculator.derive(profile, profile.strength());
		if (stats.maximumHealth() != 480 || stats.maximumStamina() != 105 || Math.abs(stats.staminaRegenerationPerSecond() - 5.1) > 0.0001 || Math.abs(stats.strengthMultiplier() - 1.12) > 0.0001) throw new IllegalStateException("Derived combat stats are incorrect.");
		if (CombatCalculator.damage(100, 1.0, 1.0, false, 500) != 50) throw new IllegalStateException("Defense calculation is incorrect.");
		if (CombatCalculator.damage(100, 1.0, 1.0, true, 0) != 150) throw new IllegalStateException("Critical calculation is incorrect.");
		if (CombatCalculator.damage(100, 1.0, 1.0, false, 10000) != 25) throw new IllegalStateException("Defense cap is incorrect.");
		System.out.println("Server-owned combat formulas verified.");
		if (CombatCalculator.damage((double) Float.MAX_VALUE, 1.0, 1.0, false, 0) != Integer.MAX_VALUE) throw new IllegalStateException("Large damage must saturate instead of overflowing.");
		if (CombatCalculator.damage(Double.MAX_VALUE, 2.0, 1.0, true, 0) != Integer.MAX_VALUE) throw new IllegalStateException("Intermediate overflow must saturate.");
	}
}
