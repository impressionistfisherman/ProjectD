package kr.dalmaru.mixin;

import kr.dalmaru.network.DalmaruNetworking;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BowItem.class)
public abstract class BowTimingMixin {
    @Redirect(method="releaseUsing",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/BowItem;getPowerForTime(I)F"))
    private float dalmaru$passiveAim(int ticks,ItemStack stack,Level level,LivingEntity user,int remaining){
        return BowItem.getPowerForTime(DalmaruNetworking.aimTicks(user,ticks));
    }
}
