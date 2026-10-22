package kr.dalmaru.mixin;

import kr.dalmaru.content.DayFieldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Monster.class)
public abstract class DayFieldSpawnMixin {
	@Inject(method = "isDarkEnoughToSpawn", at = @At("RETURN"), cancellable = true)
	private static void dalmaru$dayField(ServerLevelAccessor level, BlockPos pos, RandomSource random, CallbackInfoReturnable<Boolean> info) {
		if (Boolean.TRUE.equals(info.getReturnValue())) return;
		if (!(level instanceof ServerLevel server) || !server.isBrightOutside()) return;
		int surface = server.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
		if (pos.getY() < surface - 1) return;
		if (!DayFieldRules.allows(server.dimension().identifier().toString(), pos.getX(), pos.getZ())) return;
		info.setReturnValue(true);
	}
}
