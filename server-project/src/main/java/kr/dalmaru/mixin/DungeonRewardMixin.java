package kr.dalmaru.mixin;

import kr.dalmaru.combat.CombatRewards;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class DungeonRewardMixin {
    @Inject(method={"dropAllDeathLoot","dropExperience"},at=@At("HEAD"),cancellable=true)
    private void dalmaru$noDungeonMobLoot(CallbackInfo result) {
        if(CombatRewards.excludesRewardTags(((LivingEntity)(Object)this).entityTags()))result.cancel();
    }
}
