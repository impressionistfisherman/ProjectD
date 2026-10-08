package kr.dalmaru.combat;

import net.minecraft.world.entity.EntityType;

/** Server-owned pilot rewards for ordinary field enemies. */
public final class CombatRewards {
	private CombatRewards() { }

	public static int experienceFor(EntityType<?> entityType) {
		return experienceForDescriptionId(entityType.getDescriptionId());
	}

	public static int experienceForDescriptionId(String descriptionId) {
		if (descriptionId.equals("entity.minecraft.zombie") || descriptionId.equals("entity.minecraft.husk") || descriptionId.equals("entity.minecraft.drowned")) return 25;
		if (descriptionId.equals("entity.minecraft.spider") || descriptionId.equals("entity.minecraft.cave_spider")) return 25;
		if (descriptionId.equals("entity.minecraft.skeleton") || descriptionId.equals("entity.minecraft.stray") || descriptionId.equals("entity.minecraft.bogged")) return 30;
		if (descriptionId.equals("entity.minecraft.creeper")) return 40;
		if (descriptionId.equals("entity.minecraft.witch") || descriptionId.equals("entity.minecraft.enderman")) return 60;
		return 0;
	}
}
