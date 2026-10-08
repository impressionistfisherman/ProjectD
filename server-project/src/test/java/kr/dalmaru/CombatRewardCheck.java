package kr.dalmaru;

import kr.dalmaru.combat.CombatRewards;

public final class CombatRewardCheck {
	private CombatRewardCheck() { }

	public static void main(String[] args) {
		if (CombatRewards.experienceForDescriptionId("entity.minecraft.zombie") != 25) throw new IllegalStateException("Zombie reward is incorrect.");
		if (CombatRewards.experienceForDescriptionId("entity.minecraft.skeleton") != 30) throw new IllegalStateException("Skeleton reward is incorrect.");
		if (CombatRewards.experienceForDescriptionId("entity.minecraft.creeper") != 40) throw new IllegalStateException("Creeper reward is incorrect.");
		if (CombatRewards.experienceForDescriptionId("entity.minecraft.cow") != 0) throw new IllegalStateException("Passive mob should not grant combat experience.");
		System.out.println("Server-owned combat reward table verified.");
	}
}
