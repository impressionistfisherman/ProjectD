package kr.dalmaru.mixin;

import kr.dalmaru.network.DalmaruNetworking;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class RpgGuardMixin {
    @Inject(method="applyItemBlocking",at=@At("HEAD"),cancellable=true)
    private void dalmaru$guardOnce(ServerLevel level,DamageSource source,float amount,CallbackInfoReturnable<Float> result){
        if(DalmaruNetworking.managedGuard((LivingEntity)(Object)this))result.setReturnValue(0F);
    }
    @Inject(method="knockback",at=@At("HEAD"),cancellable=true)
    private void dalmaru$staggerResistance(double strength,double x,double z,CallbackInfo result){
        if(DalmaruNetworking.resistsStagger((LivingEntity)(Object)this))result.cancel();
    }
}
