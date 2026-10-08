package kr.dalmaru.combat;

import kr.dalmaru.account.CharacterProfile;

/** Pilot-1 formulas from GDD-003. All results are calculated on the server. */
public final class CombatCalculator {
	private CombatCalculator() { }

	public static CombatStats derive(CharacterProfile profile, int primaryStat) {
		if (primaryStat < 0) throw new IllegalArgumentException("Primary stat must not be negative.");
		int health = 200 + (profile.level() - 1) * 20 + profile.vitality() * 10;
		int stamina = 100 + profile.spirit();
		double regeneration = 5.0 + profile.spirit() * 0.02;
		double multiplier = 1.0 + primaryStat * 0.004;
		return new CombatStats(health, stamina, regeneration, multiplier);
	}

	public static int damage(int weaponAttack, double strengthMultiplier, double skillMultiplier, boolean critical, int defense) {
		return damage((double) weaponAttack, strengthMultiplier, skillMultiplier, critical, defense);
	}
	public static int damage(double weaponAttack, double strengthMultiplier, double skillMultiplier, boolean critical, int defense) {
		if (!Double.isFinite(weaponAttack) || !Double.isFinite(strengthMultiplier) || !Double.isFinite(skillMultiplier)
			|| weaponAttack < 0 || strengthMultiplier < 0 || skillMultiplier < 0 || defense < 0) throw new IllegalArgumentException("Invalid combat values.");
		double defenseRemaining = 1.0 - Math.min(0.75, defense / (defense + 500.0));
		double criticalMultiplier = critical ? 1.5 : 1.0;
		long rounded = Math.round(weaponAttack * strengthMultiplier * skillMultiplier * criticalMultiplier * defenseRemaining);
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, rounded));
	}
}
