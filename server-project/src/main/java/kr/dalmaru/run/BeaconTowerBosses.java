package kr.dalmaru.run;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class BeaconTowerBosses {
    private BeaconTowerBosses() { }

    static void style(ServerLevel level, UUID bossId, boolean finalBoss) {
        if (!(level.getEntity(bossId) instanceof Mob boss)) return;
        boss.setCanPickUpLoot(false);
        boss.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        boss.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(finalBoss ? Items.SOUL_LANTERN : Items.SHIELD));
        boss.setGlowingTag(finalBoss);
    }
}
