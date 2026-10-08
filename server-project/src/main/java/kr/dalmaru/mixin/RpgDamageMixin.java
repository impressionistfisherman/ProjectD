package kr.dalmaru.mixin;

import kr.dalmaru.network.DalmaruNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class RpgDamageMixin {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float dalmaru$damage(float amount, ServerLevel level, DamageSource source, float originalAmount) {
        return DalmaruNetworking.incomingDamage((LivingEntity)(Object)this, source, amount);
    }

    @Inject(method = "getDamageAfterArmorAbsorb", at = @At("HEAD"), cancellable = true)
    private void dalmaru$armor(DamageSource source, float amount, CallbackInfoReturnable<Float> result) {
        if (DalmaruNetworking.managedDamage((LivingEntity)(Object)this, source)) result.setReturnValue(amount);
    }
}
